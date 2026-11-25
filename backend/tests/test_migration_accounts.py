"""Storage from before accounts, and the operator's legacy command (docs/API_V1.md, section 10; ADR-0119, B3).

Every match stored before server 0.9 belongs to no account (LEGACY): every route answers 403 to
everyone until the operator gives it to an account (`legacy --assign`) or deletes it (`--delete`).
"""
import sqlite3
import time
from contextlib import closing

import pytest

from app import admin, deletion, sweep
from app.quotas import GIB
from tests.helpers import (
    OWNER, STRANGER, ScriptedAnalyzer, card_of, create_team, decide, forget_sessions, ready_video, request_job, rows,
    run_pending, serve, user_id_of, wait_for,
)
from tests.test_api import create_match, create_upload, set_upload

NEW_INDEXES = ("matches_owner", "matches_status", "uploads_unfinished", "uploads_started_by")
NEW_COLUMNS = (("matches", "status"), ("matches", "owner_user_id"), ("uploads", "touched_at"), ("uploads", "started_by"),
               ("uploads", "finalize_token"), ("uploads", "touched_uptime"), ("uploads", "finalize_started_uptime"))
ACCOUNT_TABLES = ("sessions", "login_devices", "operator_codes", "users")
# The tables of B4 (server 0.10), children first: they reference users, and storage before accounts had none of them.
TEAM_TABLES = ("pool_blocks", "memberships", "seat_pools", "teams")
TEAM_INDEXES = ("memberships_one_captain", "memberships_jersey", "memberships_user")
DAY = 24 * 3600
STALE = deletion.FINALIZE_STALE_SECONDS


@pytest.fixture
def client(storage):
    """The signed-in client without the background threads. The sweep thread writes its uptime (server_clock)
    when it starts, and a test that compares two dumps of the storage would see that write if it came
    between them."""
    with serve(storage, None) as signed_in:
        yield signed_in


def database(storage):
    return closing(sqlite3.connect(storage / "volleystat.sqlite3"))


def back_to_before_accounts(storage):
    """Turn the storage into what server 0.7 and 0.8 wrote (design 8.6): the indexes first, because SQLite
    refuses to drop an indexed column, then the columns, then the team tables and the account tables, children
    first, and the uptime of the sweep."""
    with database(storage) as db, db:
        for index in NEW_INDEXES:
            db.execute(f"DROP INDEX {index}")
        for table, column in NEW_COLUMNS:
            db.execute(f"ALTER TABLE {table} DROP COLUMN {column}")
        for table in (*TEAM_TABLES, *ACCOUNT_TABLES, "server_clock"):
            db.execute(f"DROP TABLE {table}")
    forget_sessions(storage)


# What the builds of B3 before the uptime counter wrote (up to 57e13fe): the last moment the server was seen
# running by the wall clock, the time off, what a step back of the clock kept, and no uptime columns.
EARLIER_B3 = (
    "CREATE TABLE server_clock (id INTEGER PRIMARY KEY CHECK (id = 1), last_alive REAL NOT NULL, kept_alive REAL,"
    " went_back_to REAL, behind_from REAL)",
    "CREATE TABLE server_downtimes (stopped_at REAL NOT NULL, started_at REAL NOT NULL)",
    "CREATE TABLE server_downtimes_kept (stopped_at REAL NOT NULL, started_at REAL NOT NULL)",
    "CREATE INDEX uploads_touched ON uploads(touched_at) WHERE status = 'UPLOADING'",
    "ALTER TABLE uploads ADD COLUMN kept_touched_at REAL",
    "ALTER TABLE uploads ADD COLUMN kept_finalize_started_at REAL",
)


def back_to_an_earlier_b3_build(storage, now):
    with database(storage) as db, db:
        db.execute("DROP INDEX uploads_unfinished")
        for column in ("touched_uptime", "finalize_started_uptime"):
            db.execute(f"ALTER TABLE uploads DROP COLUMN {column}")
        db.execute("DROP TABLE server_clock")
        for statement in EARLIER_B3:
            db.execute(statement)
        # The clock ran an hour ahead and went back, and a sleep and the time off of a night are recorded.
        db.execute("INSERT INTO server_clock VALUES(1, ?, ?, ?, ?)", (now, now + 3600, now, now - 180))
        db.execute("INSERT INTO server_downtimes VALUES(?, ?)", (now - 9 * 3600, now - 3600))
        db.execute("INSERT INTO server_downtimes_kept VALUES(?, ?)", (now - 9 * 3600, now + 3600))
        db.execute("UPDATE uploads SET kept_touched_at = ?", (now + 1800,))


def full_upload(client, data=b"abc"):
    upload_id = create_upload(client, create_match(client), size=len(data)).json()["uploadId"]
    assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=data).status_code == 200
    return upload_id


def write_uptime(storage, uptime):
    with database(storage) as db, db:
        db.execute("INSERT OR REPLACE INTO server_clock(id, uptime) VALUES(1, ?)", (uptime,))


class Sweep:
    """A sweeper over the app's storage whose monotonic clock the test moves, put where the routes find it."""

    def __init__(self, client):
        self.ticks = 5.0
        original = client.app.state.sweeper
        self.swept = sweep.Sweeper(original._connect, original._files, monotonic=lambda: self.ticks)
        client.app.state.sweeper = self.swept
        self.swept.resume()

    def run(self, seconds):
        """The server runs `seconds` with a pass every three minutes and one at the end; returns the closes."""
        closed = 0
        while seconds > 0:
            step = min(sweep.MAX_PASS_GAP_SECONDS, seconds)
            self.ticks += step
            closed += self.swept.run_once()["uploads"]
            seconds -= step
        return closed


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
        assert {(row["touched_at"], row["started_by"], row["finalize_token"], row["touched_uptime"],
                 row["finalize_started_uptime"]) for row in rows(storage, "SELECT * FROM uploads")} == {
            (None, None, None, None, None)}
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", unfinished) == [{"status": "UPLOADING"}]
        assert indexes(storage, "matches") >= {"matches_owner", "matches_status"}
        assert indexes(storage, "uploads") >= {"uploads_unfinished", "uploads_started_by"}
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


def test_storage_of_b3_opens_with_the_team_tables_and_keeps_every_row(storage):
    """Server 0.9 (B3) wrote no team table. B4 adds only new tables, so a start creates them with their checks
    and indexes and rebuilds nothing: users, sessions, matches, uploads and operator codes stay as they were,
    B1's operator code columns and a CAPTAIN row of a B1 test included. A second start changes nothing."""
    with serve(storage, None) as client:
        match_id = create_match(client)
        upload_id = create_upload(client, match_id, size=10).json()["uploadId"]
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abc").status_code == 200
        assert client.get("/v1/me").status_code == 200
    moment, later = "2026-11-20T10:00:00Z", "2026-11-27T10:00:00Z"
    with database(storage) as db, db:
        for table in TEAM_TABLES:
            db.execute(f"DROP TABLE {table}")
        db.execute("INSERT INTO operator_codes(code_hash, kind, signup_used, used_by_user_id, team_name, created_at,"
                   " expires_at) VALUES(?,?,?,?,?,?,?)", ("a" * 64, "CAPTAIN", 0, None, "Eski Takım", moment, later))
        db.execute("INSERT INTO operator_codes(code_hash, kind, created_at, expires_at) VALUES(?,?,?,?)",
                   ("b" * 64, "SIGNUP", moment, later))
    kept_tables = ("users", "sessions", "login_devices", "operator_codes", "matches", "uploads")

    def kept():
        with database(storage) as db:
            return {table: db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall() for table in kept_tables}

    def schema():
        with database(storage) as db:
            return {name: (kind, sql) for kind, name, sql in db.execute("SELECT type, name, sql FROM sqlite_master")}

    before, earlier_schema = kept(), schema()
    assert not set(earlier_schema) & {*TEAM_TABLES, *TEAM_INDEXES}

    with serve(storage, None) as client:
        found = schema()
        assert {name for name, (kind, _sql) in found.items() if kind == "table"} >= set(TEAM_TABLES)
        assert {name for name, (kind, _sql) in found.items() if kind == "index"} >= set(TEAM_INDEXES)
        assert "WHERE role = 'CAPTAIN'" in found["memberships_one_captain"][1]
        assert "CHECK (seat IN ('STARTER', 'SUBSTITUTE'))" in found["memberships"][1]
        assert ("CHECK ((kind = 'TEAM' AND starter_seats = 6) OR (kind = 'OPEN_MATCH' AND starter_seats = 12))"
                in found["seat_pools"][1])
        # Nothing that was there is rebuilt or rewritten.
        assert {name: entry for name, entry in found.items() if name in earlier_schema} == earlier_schema
        assert kept() == before
        assert client.get(f"/v1/matches/{match_id}").status_code == 200
        # The new tables work at once.
        team = create_team(client, name="Kuzey Spor")
        assert rows(storage, "SELECT kind, starter_seats, substitute_seats FROM seat_pools WHERE team_id=?",
                    team["id"]) == [{"kind": "TEAM", "starter_seats": 6, "substitute_seats": 2}]
    first = dump(storage)
    with serve(storage, None):
        pass
    assert dump(storage) == first, "a second start changed the storage"


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
        assert indexes(storage, "uploads") >= {"uploads_unfinished", "uploads_started_by"}
        assert rows(storage, "SELECT status, owner_user_id FROM matches") == [{"status": "LEGACY", "owner_user_id": None}]


def test_a_verification_left_by_0_8_is_abandoned_at_once_and_closes_a_day_of_uptime_after_the_first_start_of_0_9(
        storage, capsys):
    """Server 0.8 kept no uptime and wrote no token. A verification it left unfinished a month ago by the wall
    clock counts as started at the uptime of the first start, and without a token it is abandoned at once:
    legacy --delete deletes its match right after that start. The sweep closes another such verification a day
    of uptime after the first start."""
    with serve(storage, None) as client:
        held, closed = full_upload(client), full_upload(client)
    month_ago = time.time() - 30 * DAY
    for upload_id in (held, closed):
        set_upload(storage, upload_id, status="FINALIZING", finalize_started_at=month_ago)
    held_match = rows(storage, "SELECT match_id FROM uploads WHERE id=?", held)[0]["match_id"]
    back_to_before_accounts(storage)
    with serve(storage, None) as client:
        assert rows(storage, "SELECT touched_uptime, finalize_started_uptime, finalize_token FROM uploads") == [
            {"touched_uptime": None, "finalize_started_uptime": 0.0, "finalize_token": None}] * 2
        capsys.readouterr()
        assert legacy(storage, "--delete", "--match", held_match) == 0
        assert capsys.readouterr().out.strip() == "Komut 1 maçı sildi."
        assert rows(storage, "SELECT id FROM uploads") == [{"id": closed}]
        swept = Sweep(client)
        assert swept.run(DAY - 1) == 0
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", closed) == [{"status": "FINALIZING"}]
        assert swept.run(1) == 1
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", closed) == [{"status": "ABORTED"}]


def test_storage_of_an_earlier_b3_build_opens_and_its_unfinished_uploads_count_from_the_first_start(storage):
    """The builds of B3 before the uptime counter measured uptime with the wall clock. The start drops their
    clock tables and the index on the wall clock touch, and leaves the two upload columns of the kept moment
    unused. Every unfinished upload counts as touched, and every verification as started, at the uptime of the
    first start: each gets a full day of uptime from there. The start frees every verification, so the first
    complete takes one over at once. An upload stored before server 0.9 still goes at the first pass. A second
    start changes nothing."""
    with serve(storage, None) as client:
        waiting, taken, left = full_upload(client), full_upload(client), full_upload(client)
        before_accounts = create_upload(client, create_match(client), size=10).json()["uploadId"]
    week_ago = time.time() - 7 * DAY
    set_upload(storage, waiting, touched_at=week_ago)
    for upload_id in (taken, left):
        set_upload(storage, upload_id, status="FINALIZING", touched_at=week_ago, finalize_started_at=week_ago,
                   finalize_token="earlier")
    set_upload(storage, before_accounts, touched_at=None, touched_uptime=None)
    back_to_an_earlier_b3_build(storage, time.time())

    with serve(storage, None) as client:
        with database(storage) as db:
            tables = {name for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            clock_columns = [row[1] for row in db.execute("PRAGMA table_info(server_clock)")]
            upload_columns = {row[1] for row in db.execute("PRAGMA table_info(uploads)")}
        assert not tables & {"server_downtimes", "server_downtimes_kept"}
        assert clock_columns == ["id", "uptime"] and rows(storage, "SELECT * FROM server_clock") == []
        assert upload_columns >= {"kept_touched_at", "kept_finalize_started_at", "touched_uptime", "finalize_started_uptime"}
        assert "uploads_touched" not in indexes(storage, "uploads")
        assert {row["id"]: (row["touched_uptime"], row["finalize_started_uptime"]) for row in rows(
            storage, "SELECT id, touched_uptime, finalize_started_uptime FROM uploads")} == {
            waiting: (0.0, None), taken: (0.0, 0.0), left: (0.0, 0.0), before_accounts: (None, None)}
        swept = Sweep(client)
        assert rows(storage, "SELECT finalize_token FROM uploads WHERE id IN (?, ?)", taken, left) == [
            {"finalize_token": None}] * 2
        assert swept.swept.run_once() == {"uploads": 1}
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", before_accounts) == [{"status": "ABORTED"}]
        assert client.post(f"/v1/uploads/{taken}/complete").status_code == 200
        assert swept.run(DAY - 1) == 0
        assert {row["status"] for row in rows(storage, "SELECT status FROM uploads WHERE id IN (?, ?)", waiting, left)} == {
            "UPLOADING", "FINALIZING"}
        assert swept.run(1) == 2
    first = dump(storage)
    with serve(storage, None):
        pass
    assert dump(storage) == first, "a second start changed the storage"


def test_an_unfinished_upload_without_an_uptime_counts_from_the_stored_uptime_of_the_start_that_finds_it(storage):
    """A start that finds an unfinished upload with a wall clock touch but no uptime gives it the stored uptime of
    that start, so the upload gets a full day of uptime from there, however long the server ran before."""
    with serve(storage, None) as client:
        upload_id = full_upload(client)
    write_uptime(storage, 10 * DAY)
    set_upload(storage, upload_id, touched_uptime=None, touched_at=time.time() - 7 * DAY)
    with serve(storage, None):
        assert rows(storage, "SELECT touched_uptime FROM uploads") == [{"touched_uptime": 10 * DAY}]


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
    # The command reads the uptime the server stored: 3 hours.
    write_uptime(storage, 3 * 3600)
    # A request of the running server checks the file: the row holds its token.
    set_upload(storage, upload_id, status="FINALIZING", finalize_started_uptime=3 * 3600 - STALE + 1,
               finalize_token="running")
    make_legacy(storage, match_id)
    before = dump(storage)
    capsys.readouterr()
    assert legacy(storage, "--delete") == 1
    assert capsys.readouterr().err.strip() == f"Match {match_id} has an upload that is being verified."
    assert dump(storage) == before and media_files(storage) == [f"{upload_id}.bin"]
    # A verification that died 15 minutes of uptime ago does not hold the match forever. The wall clock is not read.
    set_upload(storage, upload_id, finalize_started_uptime=3 * 3600 - STALE, finalize_started_at=time.time())
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
