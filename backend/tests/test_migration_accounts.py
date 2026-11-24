"""Storage from before accounts, and the operator's legacy command (docs/API_V1.md, section 10; ADR-0119, B3).

Every match stored before server 0.9 belongs to no account (LEGACY): every route answers 403 to
everyone until the operator gives it to an account (`legacy --assign`) or deletes it (`--delete`).
"""
import sqlite3
import time
from contextlib import closing

import pytest

from app import admin, main as main_module
from app.quotas import GIB
from tests.helpers import (
    OWNER, STRANGER, ScriptedAnalyzer, card_of, decide, forget_sessions, ready_video, request_job, rows, run_pending,
    serve, user_id_of, wait_for,
)
from tests.test_api import create_match, create_upload, set_upload

NEW_INDEXES = ("matches_owner", "matches_status", "uploads_touched", "uploads_started_by")
NEW_COLUMNS = (("matches", "status"), ("matches", "owner_user_id"), ("uploads", "touched_at"), ("uploads", "started_by"))
ACCOUNT_TABLES = ("sessions", "login_devices", "operator_codes", "users")


def database(storage):
    return closing(sqlite3.connect(storage / "volleystat.sqlite3"))


def back_to_before_accounts(storage):
    """Turn the storage into what server 0.7 wrote (design 8.6): the indexes first, because SQLite
    refuses to drop an indexed column, then the columns, then the account tables, sessions first."""
    with database(storage) as db, db:
        for index in NEW_INDEXES:
            db.execute(f"DROP INDEX {index}")
        for table, column in NEW_COLUMNS:
            db.execute(f"ALTER TABLE {table} DROP COLUMN {column}")
        for table in ACCOUNT_TABLES:
            db.execute(f"DROP TABLE {table}")
    forget_sessions(storage)


def dump(storage):
    with database(storage) as db:
        tables = [name for (name,) in db.execute("SELECT name FROM sqlite_master ORDER BY name")]
        return {"schema": db.execute("SELECT type, name, sql FROM sqlite_master ORDER BY name").fetchall(),
                **{table: db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall()
                   for table in tables if (table,) in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}}


def indexes(storage, table):
    with database(storage) as db:
        return {row[1] for row in db.execute(f"PRAGMA index_list({table})")}


def make_legacy(storage, *match_ids):
    with database(storage) as db, db:
        for match_id in match_ids:
            db.execute("UPDATE matches SET status='LEGACY', owner_user_id=NULL WHERE id=?", (match_id,))


def legacy(storage, *arguments):
    return admin.main(["--storage", str(storage), "legacy", *arguments])


def media_files(storage):
    return sorted(path.name for path in (storage / "media").iterdir())


# --- migration --------------------------------------------------------------------------------------

def test_storage_from_before_accounts_gets_legacy_matches_and_the_new_columns(storage, make_clip):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert run_pending(client) == 1
        decide(client, card_of(client, job["id"], 1), "HOME", idempotencyKey="eski-karar")
        match_id = video["matchId"]
        unfinished = create_upload(client, match_id, size=10).json()["uploadId"]
        report = client.get(f"/v1/matches/{match_id}/report")
        assert report.status_code == 200
    back_to_before_accounts(storage)
    assert not indexes(storage, "matches") & set(NEW_INDEXES)

    with serve(storage, None) as client:
        assert rows(storage, "SELECT status, owner_user_id FROM matches") == [{"status": "LEGACY", "owner_user_id": None}]
        assert {(row["touched_at"], row["started_by"]) for row in rows(storage, "SELECT * FROM uploads")} == {(None, None)}
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", unfinished) == [{"status": "UPLOADING"}]
        assert indexes(storage, "matches") >= {"matches_owner", "matches_status"}
        assert indexes(storage, "uploads") >= {"uploads_touched", "uploads_started_by"}
        with database(storage) as db:
            assert {name for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table'")} >= set(ACCOUNT_TABLES)
        # Nobody sees the match, not even a fresh account of the same name.
        refused = client.get(f"/v1/matches/{match_id}/report")
        assert (refused.status_code, refused.json()["error"]["code"]) == (403, "NOT_A_MEMBER")
    first = dump(storage)
    with serve(storage, None):
        pass
    assert dump(storage) == first, "a second start changed the storage"

    assert legacy(storage, "--assign", OWNER, "--all") == 0
    with serve(storage, None) as client:
        assert client.get(f"/v1/matches/{match_id}/report").content == report.content
        assert client.get(f"/v1/matches/{match_id}").json()["status"] == "PERSONAL"


def test_an_unfinished_upload_stored_before_accounts_is_swept_at_the_first_pass(storage):
    with serve(storage, None) as client:
        upload_id = create_upload(client, create_match(client), size=10).json()["uploadId"]
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
    back_to_before_accounts(storage)
    # Its match is LEGACY and nobody can resume it, so the first pass of the sweep aborts it.
    with serve(storage, None, start_worker=True):
        wait_for(lambda: rows(storage, "SELECT status FROM uploads")[0]["status"] == "ABORTED")
        assert media_files(storage) == []


def test_the_new_indexes_are_created_after_the_columns_on_older_storage(storage):
    with database(storage) as db, db:
        db.execute("CREATE TABLE matches (id TEXT PRIMARY KEY, home_team TEXT NOT NULL, away_team TEXT NOT NULL,"
                   " created_at TEXT NOT NULL DEFAULT (datetime('now')))")
        db.execute("INSERT INTO matches(id, home_team, away_team) VALUES('m1', 'A Takımı', 'B Takımı')")
    with serve(storage, None):
        assert indexes(storage, "matches") >= {"matches_owner", "matches_status"}
        assert indexes(storage, "uploads") >= {"uploads_touched", "uploads_started_by"}
        assert rows(storage, "SELECT status, owner_user_id FROM matches") == [{"status": "LEGACY", "owner_user_id": None}]


# --- legacy --list ------------------------------------------------------------------------------------

def test_legacy_list_prints_every_legacy_match_and_no_personal_one(client, storage, capsys):
    old = create_match(client, homeTeamName="Eski Ev", awayTeamName="Eski Konuk", playedOn="2026-10-01")
    create_upload(client, old, size=7)
    bare = create_match(client, homeTeamName="Boş Ev", awayTeamName="Boş Konuk")
    personal = create_match(client, homeTeamName="Kişisel Ev", awayTeamName="Kişisel Konuk")
    make_legacy(storage, old, bare)
    capsys.readouterr()

    assert legacy(storage, "--list") == 0
    out = capsys.readouterr().out
    lines = {line.split("  ")[0]: line for line in out.splitlines() if line.split("  ")[0] in (old, bare, personal)}
    assert set(lines) == {old, bare}
    assert "2026-10-01" in lines[old] and "Eski Ev" in lines[old] and "Eski Konuk" in lines[old]
    assert lines[old].split("  ")[-3:] == ["1", "0", "7"]
    assert lines[bare].split("  ")[2] == "-" and lines[bare].split("  ")[-3:] == ["0", "0", "0"]
    assert "Sunucu hiçbir hesaba bağlı olmayan 2 maç için 7 bayt tutuyor." in out


def test_legacy_list_says_so_when_there_is_none(client, storage, capsys):
    create_match(client)
    capsys.readouterr()
    assert legacy(storage, "--list") == 0
    assert capsys.readouterr().out.strip() == "Hiçbir hesaba bağlı olmayan maç yok."


# --- legacy --assign ----------------------------------------------------------------------------------

def test_legacy_assign_all_makes_every_legacy_match_a_personal_match_of_the_only_account(client, storage, capsys):
    first = create_match(client, homeTeamName="Birinci Ev", awayTeamName="Birinci Konuk", playedOn="2026-10-01")
    second = create_match(client, homeTeamName="İkinci Ev", awayTeamName="İkinci Konuk")
    create_upload(client, first, size=9)
    make_legacy(storage, first, second)
    assert client.get(f"/v1/matches/{first}").status_code == 403
    capsys.readouterr()

    assert legacy(storage, "--assign", OWNER, "--all") == 0
    # The command names every match it binds, so the operator sees what the account now holds.
    assert capsys.readouterr().out.splitlines() == [
        f"{first}  2026-10-01  Birinci Ev  Birinci Konuk",
        f"{second}  -  İkinci Ev  İkinci Konuk",
        f"Komut 2 maçı {OWNER} hesabına bağladı (9 bayt).",
    ]
    owner_id = user_id_of(storage, OWNER)
    assert rows(storage, "SELECT id, status, owner_user_id FROM matches ORDER BY rowid") == [
        {"id": first, "status": "PERSONAL", "owner_user_id": owner_id},
        {"id": second, "status": "PERSONAL", "owner_user_id": owner_id},
    ]
    assert client.get(f"/v1/matches/{first}").json()["status"] == "PERSONAL"


def test_legacy_assign_all_refuses_a_server_with_more_than_one_account_and_changes_nothing(client, storage, stranger, capsys):
    """Server 0.8 read no session on the match routes, so nothing tells whose a LEGACY match was. With two
    accounts on the server, binding every one to one account could hand one person's videos to another."""
    first, second = create_match(client), create_match(client)
    make_legacy(storage, first, second)
    before = dump(storage)
    capsys.readouterr()
    assert legacy(storage, "--assign", STRANGER, "--all") == 1
    captured = capsys.readouterr()
    assert captured.err.strip() == ("This server has 2 accounts, so --all could give one person's matches to another."
                                    " Name each match with --match; legacy --list shows their teams and dates.")
    assert captured.out == ""
    assert dump(storage) == before


def test_legacy_assign_needs_a_match_or_all(storage):
    for arguments in (("--assign", OWNER), ("--assign", OWNER, "--all", "--match", "m1"), ("--list", "--all"),
                      ("--delete", "--all")):
        with pytest.raises(SystemExit) as stopped:
            legacy(storage, *arguments)
        assert stopped.value.code == 2, arguments


def test_legacy_assign_with_match_moves_only_those(client, storage, stranger, capsys):
    first, second, third = create_match(client), create_match(client), create_match(client)
    others = stranger.post("/v1/matches", json={"homeTeamName": "Yabancı Ev", "awayTeamName": "Yabancı Konuk"}).json()["id"]
    make_legacy(storage, first, second, third)
    capsys.readouterr()
    assert legacy(storage, "--assign", OWNER, "--match", first, "--match", third) == 0
    out = capsys.readouterr().out.splitlines()
    assert [line.split("  ")[0] for line in out[:-1]] == [first, third]
    assert out[-1] == f"Komut 2 maçı {OWNER} hesabına bağladı (0 bayt)."
    assert {row["id"]: row["status"] for row in rows(storage, "SELECT id, status FROM matches")} == {
        first: "PERSONAL", second: "LEGACY", third: "PERSONAL", others: "PERSONAL"}
    assert rows(storage, "SELECT owner_user_id FROM matches WHERE id=?", others) == [
        {"owner_user_id": user_id_of(storage, STRANGER)}]


def test_legacy_assign_refuses_an_unknown_username_and_changes_nothing(client, storage, capsys):
    match_id = create_match(client)
    make_legacy(storage, match_id)
    before = dump(storage)
    capsys.readouterr()
    assert legacy(storage, "--assign", "hic.yok", "--match", match_id) == 1
    captured = capsys.readouterr()
    assert captured.err.strip() == "No account has the username 'hic.yok'." and captured.out == ""
    assert dump(storage) == before


def test_legacy_assign_refuses_a_match_that_is_not_legacy_and_changes_nothing(client, storage, capsys):
    legacy_id, personal = create_match(client), create_match(client)
    make_legacy(storage, legacy_id)
    before = dump(storage)
    capsys.readouterr()
    for unknown in (personal, "no-such-match"):
        assert legacy(storage, "--assign", OWNER, "--match", legacy_id, "--match", unknown) == 1
        captured = capsys.readouterr()
        assert captured.err.strip() == f"Match {unknown} is not a legacy match." and captured.out == ""
        assert dump(storage) == before


# --- legacy --delete ----------------------------------------------------------------------------------

def database_bytes(storage):
    return b"".join(path.read_bytes() for path in sorted(storage.glob("volleystat.sqlite3*")))


def test_legacy_delete_removes_rows_and_bytes_and_leaves_no_trace(storage, make_clip, capsys):
    with serve(storage, ScriptedAnalyzer()) as client:
        video = ready_video(client, make_clip)
        job = request_job(client, video["id"])
        assert run_pending(client) == 1
        decide(client, card_of(client, job["id"], 1), "HOME")
        old = video["matchId"]
        with database(storage) as db, db:
            db.execute("UPDATE matches SET home_team='SilinecekEvQZ1', away_team='SilinecekKonukQZ2' WHERE id=?", (old,))
        partial = create_upload(client, old, size=10, fileName="EskiDosyaQZ3.mp4").json()["uploadId"]
        kept = create_match(client, homeTeamName="KalanEvQZ4", awayTeamName="KalanKonukQZ5")
        bare = create_match(client, homeTeamName="BosEvQZ6", awayTeamName="BosKonukQZ7")
        make_legacy(storage, old, bare)
        assert len(media_files(storage)) == 2
        before = database_bytes(storage)
        assert all(mark.encode() in before for mark in ("SilinecekEvQZ1", "EskiDosyaQZ3.mp4", "BosEvQZ6"))
        capsys.readouterr()

        # A second connection that stays open keeps SQLite from clearing its write-ahead log by itself,
        # as on a running server: only the command's own emptying of the log removes the old pages.
        bystander = sqlite3.connect(storage / "volleystat.sqlite3")
        try:
            assert bystander.execute("SELECT COUNT(*) FROM matches").fetchone() == (3,)
            assert legacy(storage, "--delete") == 0
            after = database_bytes(storage)
        finally:
            bystander.close()
        assert capsys.readouterr().out.strip() == "Komut 2 maçı sildi."
        assert media_files(storage) == []
        for table in ("uploads", "videos", "analysis_jobs", "rallies", "review_cards", "review_decisions"):
            assert rows(storage, f"SELECT 1 FROM {table}") == [], table
        assert [row["id"] for row in rows(storage, "SELECT id FROM matches")] == [kept]
        for mark in ("SilinecekEvQZ1", "SilinecekKonukQZ2", "EskiDosyaQZ3.mp4", "BosEvQZ6", "BosKonukQZ7", old, partial):
            assert mark.encode() not in after, mark
        assert b"KalanEvQZ4" in after


def test_legacy_delete_with_match_deletes_only_those(client, storage, capsys):
    first, second = create_match(client), create_match(client)
    make_legacy(storage, first, second)
    capsys.readouterr()
    assert legacy(storage, "--delete", "--match", second) == 0
    assert capsys.readouterr().out.strip() == "Komut 1 maçı sildi."
    assert [row["id"] for row in rows(storage, "SELECT id FROM matches")] == [first]
    assert legacy(storage, "--delete", "--match", first, "--match", "no-such-match") == 1
    assert capsys.readouterr().err.strip() == "Match no-such-match is not a legacy match."
    assert [row["id"] for row in rows(storage, "SELECT id FROM matches")] == [first]


def test_legacy_delete_refuses_a_match_whose_upload_is_being_verified(client, storage, capsys):
    match_id = create_match(client)
    upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
    client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde")
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=time.time())
    make_legacy(storage, match_id)
    before = dump(storage)
    capsys.readouterr()
    assert legacy(storage, "--delete") == 1
    assert capsys.readouterr().err.strip() == f"Match {match_id} has an upload that is being verified."
    assert dump(storage) == before and media_files(storage) == [f"{upload_id}.bin"]
    # A verification that died long ago does not hold the match forever.
    set_upload(storage, upload_id, finalize_started_at=time.time() - main_module.FINALIZE_STALE_SECONDS - 1)
    assert legacy(storage, "--delete") == 0
    assert media_files(storage) == []


def test_legacy_needs_exactly_one_action(storage, capsys):
    for arguments in ((), ("--list", "--delete"), ("--assign", OWNER, "--list"), ("--list", "--match", "m1")):
        with pytest.raises(SystemExit) as stopped:
            legacy(storage, *arguments)
        assert stopped.value.code == 2, arguments


def test_an_assigned_match_counts_on_its_owners_quota(client, storage, monkeypatch):
    from app import quotas
    monkeypatch.setattr(quotas, "free_bytes", lambda path: 1024 ** 5)
    old = create_match(client)
    assert create_upload(client, old, size=4 * GIB).status_code == 201
    make_legacy(storage, old)
    # LEGACY bytes count on nobody: the owner still has the whole 8 GiB.
    fresh = create_match(client)
    assert create_upload(client, fresh, size=4 * GIB).status_code == 201
    assert legacy(storage, "--assign", OWNER, "--match", old) == 0
    refused = create_upload(client, fresh, size=1)
    assert refused.status_code == 409
    assert refused.json()["error"]["details"] == {"scope": "PERSONAL", "usedBytes": 8 * GIB, "limitBytes": 8 * GIB}
