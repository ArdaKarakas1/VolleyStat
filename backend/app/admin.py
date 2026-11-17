"""Operator commands for the local server: python -m app.admin [--storage PATH] <command>.

signup-code              print a one-time code that opens one registration (valid 7 days)
reset-password USERNAME  print a one-time temporary password and close every session of the account

The commands work on the storage folder of the server (VOLLEYSTAT_STORAGE, or backend/storage).
They may run while the server runs.
"""
from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from . import accounts


def default_storage() -> Path:
    return Path(os.environ.get("VOLLEYSTAT_STORAGE") or (Path(__file__).parent.parent / "storage")).resolve()


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m app.admin", description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--storage", type=Path, default=None, help="Storage folder of the server")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("signup-code", help="Print a one-time code that opens one registration")
    reset = commands.add_parser("reset-password", help="Print a temporary password and close every session")
    reset.add_argument("username")
    args = parser.parse_args(argv)
    storage = (args.storage or default_storage()).resolve()
    storage.mkdir(parents=True, exist_ok=True, mode=0o700)
    db_path = storage / "volleystat.sqlite3"
    if args.command == "signup-code":
        print("Kayıt kodu (7 gün geçerli, bir kayıt açar):")
        print(accounts.issue_operator_code(db_path, "SIGNUP"))
        return 0
    temporary = accounts.reset_password(db_path, args.username)
    if temporary is None:
        print(f"No account has the username {args.username!r}.", file=sys.stderr)
        return 1
    print("Geçici şifre (kişi giriş yaptıktan sonra uygulama yeni bir şifre önerir):")
    print(temporary)
    return 0


if __name__ == "__main__":
    sys.exit(main())
