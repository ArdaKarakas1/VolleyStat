"""Storage limits of personal video and the free-disk floor (docs/API_V1.md, section 10; design 6.5).

Sizes count as the uploads declared them: an upload row counts at `expected_size` while it is
UPLOADING, FINALIZING or COMPLETED (a live video, a quarantined one included, because its bytes stay
on disk). A declared size bounds the bytes the server accepts, so a low declaration can not get
around the limit; a high one holds the space until the sweep closes the upload after a day.

The checks of `initiate_upload` run inside its write transaction, so two uploads started at once
can not both pass on the same free bytes.
"""
from __future__ import annotations

import os
import shutil
from pathlib import Path

from .errors import fail

GIB = 1024 ** 3

# How the account opened (users.signup_via) sets its personal limit. An account opened with an
# operator code (or in the open mode of tests) gets 8 GiB, two maximum-size videos. An account
# opened through a link gets 2 GiB and one unfinished upload. Any other value gets the smaller
# limit, so a value a later slice adds is never more generous by mistake.
CODE_ACCOUNTS = frozenset({"SIGNUP_CODE", "CAPTAIN_CODE", "OPEN"})
CODE_ACCOUNT_LIMIT = 8 * GIB
LINK_ACCOUNT_LIMIT = 2 * GIB
LINK_ACCOUNT_UNFINISHED = 1
# Every account's personal video together.
PERSONAL_POOL = 40 * GIB
DEFAULT_FLOOR_GIB = 20

COUNTED = ("UPLOADING", "FINALIZING", "COMPLETED")
UNFINISHED = ("UPLOADING", "FINALIZING")


def settings() -> int:
    """The free-disk floor in bytes, from VOLLEYSTAT_DISK_FLOOR_GIB (whole GiB, 0 or more; default 20)."""
    text = os.environ.get("VOLLEYSTAT_DISK_FLOOR_GIB", str(DEFAULT_FLOOR_GIB)).strip()
    if not (text.isascii() and text.isdigit()):
        raise ValueError(f"VOLLEYSTAT_DISK_FLOOR_GIB must be a whole number of GiB, 0 or more, not {text!r}")
    return int(text) * GIB


def free_bytes(path: Path) -> int:
    """Free space on the disk of the storage folder. The one function the tests patch."""
    return shutil.disk_usage(path).free


def personal_limit(signup_via: str) -> int:
    return CODE_ACCOUNT_LIMIT if signup_via in CODE_ACCOUNTS else LINK_ACCOUNT_LIMIT


def unfinished_limit(signup_via: str) -> int | None:
    """How many unfinished personal uploads the account may hold at once, or None for no limit."""
    return None if signup_via in CODE_ACCOUNTS else LINK_ACCOUNT_UNFINISHED


def _personal(db, owner_id: str | None, statuses: tuple[str, ...], what: str) -> int:
    owner = "" if owner_id is None else " AND m.owner_user_id = ?"
    marks = ",".join("?" * len(statuses))
    return db.execute(
        f"SELECT {what} FROM uploads u JOIN matches m ON m.id = u.match_id"
        f" WHERE m.status = 'PERSONAL'{owner} AND u.status IN ({marks})",
        (*(() if owner_id is None else (owner_id,)), *statuses)).fetchone()[0]


def personal_used(db, user_id: str) -> int:
    """Live personal videos and unfinished personal uploads of the account, at their declared size.

    Counted by the owner of the match, so a LEGACY match the operator gave to the account counts too.
    """
    return _personal(db, user_id, COUNTED, "COALESCE(SUM(u.expected_size), 0)")


def personal_unfinished(db, user_id: str) -> int:
    return _personal(db, user_id, UNFINISHED, "COUNT(*)")


def pool_used(db) -> int:
    """Every account's personal video. LEGACY bytes belong to no account and count only on the disk."""
    return _personal(db, None, COUNTED, "COALESCE(SUM(u.expected_size), 0)")


def check_upload(db, caller, declared: int, storage: Path, floor: int) -> None:
    """Refuse a new personal upload of `declared` bytes, in this order: the unfinished uploads, the
    account's limit, the server-wide pool, then the free-disk floor. The order runs from what the
    person can change to what only the operator can."""
    used, limit = personal_used(db, caller.id), personal_limit(caller.signup_via)
    most = unfinished_limit(caller.signup_via)
    if most is not None and personal_unfinished(db, caller.id) >= most:
        fail("QUOTA_EXCEEDED", "This account already has an unfinished upload", 409,
             scope="PERSONAL", reason="UNFINISHED_UPLOAD", usedBytes=used, limitBytes=limit)
    if used + declared > limit:
        fail("QUOTA_EXCEEDED", "The personal video of this account would pass its limit", 409,
             scope="PERSONAL", usedBytes=used, limitBytes=limit)
    pooled = pool_used(db)
    if pooled + declared > PERSONAL_POOL:
        fail("QUOTA_EXCEEDED", "The personal video of all accounts would pass the server's limit", 409,
             scope="SERVER", pool="PERSONAL", usedBytes=pooled, limitBytes=PERSONAL_POOL)
    # No free-space number goes to the client: only the floor it met.
    if free_bytes(storage) - declared < floor:
        fail("STORAGE_FULL", "The upload would take the free space of the server's disk below its floor", 507,
             floorBytes=floor)


def check_chunk(storage: Path, length: int, floor: int) -> None:
    """Refuse a chunk that would take the free space below half the floor (507, nothing written)."""
    half = floor // 2
    if free_bytes(storage) - length < half:
        fail("STORAGE_FULL", "The chunk would take the free space of the server's disk below its floor", 507,
             floorBytes=half)
