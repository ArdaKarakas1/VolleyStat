"""Storage limits of personal video and the free-disk floor (docs/API_V1.md, section 10; design 6.5).

Sizes count as the uploads declared them: an upload row counts at `expected_size` while it is
UPLOADING, FINALIZING or COMPLETED (a live video, a quarantined one included, because its bytes stay
on disk). A declared size bounds the bytes the server accepts, so a low declaration can not get
around the limit; a high one holds the space until the sweep closes the upload after a day.

The free-disk floor is a reservation: the start of an upload subtracts from the free space the bytes
every unfinished upload still has to send (LEGACY ones included). The checks of `initiate_upload` run
inside its write transaction, so two uploads, started one after the other or at once, can not both
pass on the same free bytes. A program that fills the disk from outside is met only by the chunk
check at half the floor.
"""
from __future__ import annotations

import os
import shutil
from pathlib import Path

from .errors import fail

GIB = 1024 ** 3

# How the account opened (users.signup_via) sets its personal quota. Only an account opened with an
# operator code (SIGNUP_CODE) gets 8 GiB, two maximum-size videos, and no limit on unfinished uploads.
# Every other value (OPEN, INVITE, OPEN_MATCH, CAPTAIN_CODE or a value a later slice adds) gets 4 GiB,
# one maximum-size video (main.MAX_VIDEO_BYTES), and one unfinished upload, so a value nobody planned
# for is never more generous by mistake.
PERSONAL_LIMIT_CODE = 8 * GIB
PERSONAL_LIMIT_OTHER = 4 * GIB
PERSONAL_UNFINISHED_OTHER = 1
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


def personal_quota(signup_via: str) -> tuple[int, int | None]:
    """The personal quota of an account: its limit in bytes, and how many unfinished personal uploads
    it may hold at once (None: no limit)."""
    if signup_via == "SIGNUP_CODE":
        return PERSONAL_LIMIT_CODE, None
    return PERSONAL_LIMIT_OTHER, PERSONAL_UNFINISHED_OTHER


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


def still_owed(db) -> int:
    """The bytes every unfinished upload, of any match, has still to send: what they will take from the disk."""
    return db.execute("SELECT COALESCE(SUM(MAX(expected_size - next_offset, 0)), 0) FROM uploads"
                      " WHERE status IN ('UPLOADING', 'FINALIZING')").fetchone()[0]


def check_upload(db, caller, declared: int, storage: Path, floor: int) -> None:
    """Refuse a new personal upload of `declared` bytes, in this order: the unfinished uploads, the
    account's limit, the server-wide pool, then the free-disk floor. The order runs from what the
    person can change to what only the operator can."""
    limit, most = personal_quota(caller.signup_via)
    used = personal_used(db, caller.id)
    if most is not None and personal_unfinished(db, caller.id) >= most:
        fail("QUOTA_EXCEEDED", "This account already has an unfinished upload", 409,
             scope="PERSONAL", reason="UNFINISHED_UPLOAD", usedBytes=used, limitBytes=limit)
    if used + declared > limit:
        fail("QUOTA_EXCEEDED", "The personal video of this account would pass its limit", 409,
             scope="PERSONAL", usedBytes=used, limitBytes=limit)
    # The answer names only the limit: the sum would tell one account how much the others store.
    if pool_used(db) + declared > PERSONAL_POOL:
        fail("QUOTA_EXCEEDED", "The personal video of all accounts would pass the server's limit", 409,
             scope="SERVER", pool="PERSONAL", limitBytes=PERSONAL_POOL)
    # No free-space number goes to the client: only the floor it met.
    if free_bytes(storage) - still_owed(db) - declared < floor:
        fail("STORAGE_FULL", "The upload would take the free space of the server's disk below its floor", 507,
             floorBytes=floor)


def check_chunk(storage: Path, length: int, floor: int) -> None:
    """Refuse a chunk that would take the free space below half the floor (507, nothing written)."""
    half = floor // 2
    if free_bytes(storage) - length < half:
        fail("STORAGE_FULL", "The chunk would take the free space of the server's disk below its floor", 507,
             floorBytes=half)
