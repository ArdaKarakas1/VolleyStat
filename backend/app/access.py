"""Who may see and change what (docs/API_V1.md, section 10; ADR-0119, B3).

Every route except the three public ones runs `accounts.current_user` before it reads anything.
A route then finds its object, resolves it to its match with `match_of` and calls `require` before
any check that names a version, a state, an offset, a key or a quota. An object that does not exist
answers 404 with its own code; an object the caller may not see answers 403 NOT_A_MEMBER with no
details, never 404, because the app reads a 404 as "deleted".

B3 has one kind of match an account may see: its own PERSONAL match. `allowed` is a dispatch on the
match's status with a default of no, so LEGACY rows, and any status a later slice adds before it adds
its rule, stay closed to everyone.
"""
from __future__ import annotations

import sqlite3
from typing import TYPE_CHECKING, Callable

from .errors import fail

if TYPE_CHECKING:
    from .accounts import Caller

READ, REVIEW = "READ", "REVIEW"
ROLES = (READ, REVIEW)
PUBLIC = frozenset({("GET", "/v1/health"), ("POST", "/v1/auth/register"), ("POST", "/v1/auth/login")})

# kind -> (the query that returns the match row of the object, the 404 code, its message). The
# messages repeat the ones the routes sent before access existed (find_upload, find_video, ...).
_TARGETS: dict[str, tuple[str, str, str]] = {
    "match": ("SELECT * FROM matches WHERE id=?", "MATCH_NOT_FOUND", "Match does not exist"),
    "upload": ("SELECT m.* FROM uploads u JOIN matches m ON m.id = u.match_id WHERE u.id=?",
               "UPLOAD_NOT_FOUND", "Upload does not exist"),
    "video": ("SELECT m.* FROM videos v JOIN matches m ON m.id = v.match_id WHERE v.id=?",
              "VIDEO_NOT_FOUND", "Video does not exist"),
    "job": ("SELECT m.* FROM analysis_jobs j JOIN matches m ON m.id = j.match_id WHERE j.id=?",
            "ANALYSIS_NOT_FOUND", "Analysis job does not exist"),
    "card": ("SELECT m.* FROM review_cards c JOIN analysis_jobs j ON j.id = c.job_id JOIN matches m ON m.id = j.match_id"
             " WHERE c.id=?", "CARD_NOT_FOUND", "Review card does not exist"),
    "rally": ("SELECT m.* FROM rallies r JOIN analysis_jobs j ON j.id = r.job_id JOIN matches m ON m.id = j.match_id"
              " WHERE r.id=?", "RALLY_NOT_FOUND", "Rally does not exist"),
}


def _owner(caller: Caller, match: sqlite3.Row, role: str) -> bool:
    return match["owner_user_id"] is not None and match["owner_user_id"] == caller.id


# status -> who may act on such a match in a role. READ and REVIEW have the same rule in B3 (the
# owner); every route still names the role it needs, so a later slice changes only this table.
_RULES: dict[str, Callable[[Caller, sqlite3.Row, str], bool]] = {
    "PERSONAL": _owner,
}


def match_of(db, kind: str, object_id: str) -> sqlite3.Row:
    """The match row an object belongs to, or 404 with the object's own code."""
    query, code, message = _TARGETS[kind]
    match = db.execute(query, (object_id,)).fetchone()
    if match is None:
        fail(code, message, 404)
    return match


def allowed(caller: Caller, match: sqlite3.Row, role: str) -> bool:
    if role not in ROLES:
        raise ValueError(f"Unknown access role {role!r}")
    rule = _RULES.get(match["status"])
    return rule is not None and rule(caller, match, role)


def require(db, caller: Caller, kind: str, object_id: str, role: str) -> sqlite3.Row:
    """The match of the object when the caller may act on it in `role`; 404 or 403 otherwise.

    The 403 carries no details, so nothing about the match reaches a caller who may not see it.
    """
    match = match_of(db, kind, object_id)
    if not allowed(caller, match, role):
        fail("NOT_A_MEMBER", "This account can not see this match", 403)
    return match


def session_user(db, caller: Caller) -> None:
    """Read the caller's session again inside a write transaction: 401 when it ended in the meantime.

    A write that raced a logout, a password change or the deletion of the account must not write a
    row that names an account nobody can act for any more.
    """
    # Imported here: accounts imports the deletion module, which imports the job queue, which imports this one.
    from . import accounts
    accounts.still_signed_in(db, caller.token_hash, accounts.now())
