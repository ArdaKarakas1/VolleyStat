"""Operator commands for the local server: python -m app.admin [--storage PATH] <command>.

signup-code              print a one-time code that opens one registration (valid 7 days)
reset-password USERNAME  print a one-time temporary password and close every session of the account
legacy --list            list the matches stored before server 0.9, which belong to no account
legacy --assign USERNAME --match ID [--match ID]...
                         make the named matches personal matches of USERNAME
legacy --assign USERNAME --all
                         make every one of them a personal match of USERNAME, only on a server
                         with a single account
legacy --delete [--match ID]...
                         delete those matches with their uploads, videos, analyses and bytes

The commands work on the storage folder of the server (VOLLEYSTAT_STORAGE, or backend/storage).
They may run while the server runs.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from . import accounts, deletion, sweep
from .db import connector, empty_write_ahead_log, migrate


class _Refused(Exception):
    """A check of the legacy command failed inside its transaction; nothing was changed."""


def default_storage() -> Path:
    return Path(os.environ.get("VOLLEYSTAT_STORAGE") or (Path(__file__).parent.parent / "storage")).resolve()


def legacy_list(db_path: Path) -> int:
    with connector(db_path)() as db:
        found = db.execute(
            "SELECT m.id, m.created_at, m.played_on, m.home_team, m.away_team,"
            " (SELECT COUNT(*) FROM uploads u WHERE u.match_id = m.id) AS uploads,"
            " (SELECT COUNT(*) FROM videos v WHERE v.match_id = m.id) AS videos,"
            " (SELECT COALESCE(SUM(u.expected_size), 0) FROM uploads u WHERE u.match_id = m.id"
            "  AND u.status IN ('UPLOADING', 'FINALIZING', 'COMPLETED')) AS bytes"
            " FROM matches m WHERE m.status = 'LEGACY' ORDER BY m.created_at, m.rowid").fetchall()
    if not found:
        print("Hiçbir hesaba bağlı olmayan maç yok.")
        return 0
    print("  ".join(("Maç kimliği", "Oluşturulma", "Maç günü", "Ev sahibi", "Konuk", "Yükleme", "Video", "Bayt")))
    for row in found:
        print("  ".join(str(value) for value in (row["id"], row["created_at"], row["played_on"] or "-", row["home_team"],
                                                  row["away_team"], row["uploads"], row["videos"], row["bytes"])))
    print(f"Sunucu hiçbir hesaba bağlı olmayan {len(found)} maç için {sum(row['bytes'] for row in found)} bayt tutuyor.")
    return 0


def _legacy_ids(db, wanted: list[str] | None) -> list[str]:
    """The LEGACY matches the command acts on: every one, or the named ones, each of which must be LEGACY."""
    legacy = [row["id"] for row in db.execute("SELECT id FROM matches WHERE status='LEGACY' ORDER BY rowid")]
    if wanted is None:
        return legacy
    for match_id in wanted:
        if match_id not in legacy:
            raise _Refused(f"Match {match_id} is not a legacy match.")
    return list(dict.fromkeys(wanted))


def legacy_assign(db_path: Path, username: str, wanted: list[str] | None) -> int:
    """Give LEGACY matches to one account: the named ones, or every one (`wanted` None, from --all).

    Server 0.8 read no session on the match routes, so nothing on the server tells which account
    uploaded a LEGACY match. --all therefore runs only on a server with a single account: with more,
    it could hand one person's videos to another, and the operator names each match instead."""
    # The checks run in the write transaction that changes the rows, so the server can not change a
    # match between a check and the change.
    with connector(db_path)(write=True) as db:
        user = db.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone()
        if user is None:
            raise _Refused(f"No account has the username {username!r}.")
        if wanted is None:
            count = db.execute("SELECT COUNT(*) FROM users").fetchone()[0]
            if count > 1:
                raise _Refused(f"This server has {count} accounts, so --all could give one person's matches to another."
                               " Name each match with --match; legacy --list shows their teams and dates.")
        chosen = _legacy_ids(db, wanted)
        bound = [db.execute("SELECT id, played_on, home_team, away_team FROM matches WHERE id=?", (match_id,)).fetchone()
                 for match_id in chosen]
        for match_id in chosen:
            db.execute("UPDATE matches SET status='PERSONAL', owner_user_id=? WHERE id=? AND status='LEGACY'",
                       (user["id"], match_id))
        # No quota check: the operator decides, and the number of bytes says what the account now holds.
        marks = ",".join("?" * len(chosen))
        held = db.execute(f"SELECT COALESCE(SUM(expected_size), 0) FROM uploads WHERE match_id IN ({marks})"
                          " AND status IN ('UPLOADING', 'FINALIZING', 'COMPLETED')", chosen).fetchone()[0] if chosen else 0
    for row in bound:
        print("  ".join(str(value) for value in (row["id"], row["played_on"] or "-", row["home_team"], row["away_team"])))
    print(f"Komut {len(chosen)} maçı {username} hesabına bağladı ({held} bayt).")
    return 0


def legacy_delete(db_path: Path, storage: Path, wanted: list[str] | None) -> int:
    files = deletion.MediaFiles(storage)
    with connector(db_path)(write=True) as db:
        chosen = _legacy_ids(db, wanted)
        # The uptime the server stored at its last pass or stop. A running server has run on since its last
        # pass, so a verification looks younger here than it is, never older.
        at = sweep.stored_uptime(db)
        for match_id in chosen:
            if deletion.finalizing(db, match_id, at):
                raise _Refused(f"Match {match_id} has an upload that is being verified.")
        removed = [deletion.delete_match_rows(db, files, match_id) for match_id in chosen]
        # The bytes go before the commit, as with every deletion. A running analysis of a deleted job
        # stops at its next heartbeat: this is another process and can not reach the worker.
        for each in removed:
            each.unlink()
    empty_write_ahead_log(db_path)
    print(f"Komut {len(chosen)} maçı sildi.")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.admin", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--storage", type=Path, default=None, help="Storage folder of the server")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("signup-code", help="Print a one-time code that opens one registration")
    reset = commands.add_parser("reset-password", help="Print a temporary password and close every session")
    reset.add_argument("username")
    legacy = commands.add_parser("legacy", help="List, assign or delete the matches that belong to no account")
    action = legacy.add_mutually_exclusive_group(required=True)
    action.add_argument("--list", action="store_true", help="List the matches that belong to no account")
    action.add_argument("--assign", metavar="USERNAME", help="Make them personal matches of this account")
    action.add_argument("--delete", action="store_true", help="Delete them with their uploads, videos and bytes")
    legacy.add_argument("--match", metavar="ID", action="append", help="Act on this match only (repeatable)")
    legacy.add_argument("--all", action="store_true",
                        help="With --assign: every match that belongs to no account (a server with one account only)")
    args = parser.parse_args(argv)
    if args.command == "legacy":
        if args.list and args.match:
            legacy.error("--match can not be used with --list")
        if args.all and args.assign is None:
            legacy.error("--all goes only with --assign")
        if args.assign is not None and not args.match and not args.all:
            legacy.error("--assign needs --match ID for each match, or --all on a server with one account")
        if args.all and args.match:
            legacy.error("--all can not be used with --match")
    storage = (args.storage or default_storage()).resolve()
    storage.mkdir(parents=True, exist_ok=True, mode=0o700)
    db_path = storage / "volleystat.sqlite3"
    if args.command == "signup-code":
        print("Kayıt kodu (7 gün geçerli, bir kayıt açar):")
        print(accounts.issue_operator_code(db_path, "SIGNUP"))
        return 0
    if args.command == "legacy":
        migrate(db_path)
        try:
            if args.list:
                return legacy_list(db_path)
            if args.assign is not None:
                return legacy_assign(db_path, args.assign, None if args.all else args.match)
            return legacy_delete(db_path, storage, args.match)
        except _Refused as refusal:
            print(str(refusal), file=sys.stderr)
            return 1
    temporary = accounts.reset_password(db_path, args.username)
    if temporary is None:
        print(f"No account has the username {args.username!r}.", file=sys.stderr)
        return 1
    print("Geçici şifre (kişi giriş yaptıktan sonra uygulama yeni bir şifre önerir):")
    print(temporary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
