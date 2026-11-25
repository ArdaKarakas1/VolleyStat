"""Where the bytes of uploads and videos live, and what deleting a match or a team removes.

The routes, the analysis worker, the upload sweep, account deletion and the operator's legacy
command share these, so every deletion removes the same rows and the same files.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .jobs import ACTIVE_STATES

# A verification that started this many seconds of server uptime ago (sweep.py) is treated as abandoned (its
# request hangs) and may be taken over by the next complete call. A younger one holds its match against deletion.
# A verification without a token is abandoned at once: the start of the server frees every verification that an
# earlier process left (sweep.Sweeper.resume), and server 0.8 wrote no token.
FINALIZE_STALE_SECONDS = 15 * 60


class MediaFiles:
    """The file of every upload and video under one storage folder."""

    def __init__(self, root: Path):
        self.root = root

    def upload(self, upload_id: str) -> Path:
        """One file per upload for its whole life: finalizing never moves it."""
        return self.root / "media" / f"{upload_id}.bin"

    def video(self, record) -> Path:
        if record["storage_key"]:
            return self.root / record["storage_key"]
        return self.root / "videos" / f"{record['id']}.bin"  # layout of the 0.2 preview

    def old_part(self, upload_id: str) -> Path:
        """Where the 0.2 preview kept an unfinished upload. Nothing writes there now; a deletion still clears it."""
        return self.root / "uploads" / f"{upload_id}.part"


@dataclass
class Removed:
    """What a deletion took out of the database: the files to unlink and the analysis runs to stop."""
    paths: list[Path] = field(default_factory=list)
    pending_job_ids: list[str] = field(default_factory=list)

    def unlink(self) -> None:
        for path in self.paths:
            path.unlink(missing_ok=True)


def still_verifying(upload, uptime: float) -> bool:
    """True while the FINALIZING upload's verification holds a token and started less than FINALIZE_STALE_SECONDS
    of server uptime before `uptime`. A row without a token, or without the uptime of its start (stored before
    server 0.9), is abandoned."""
    started = upload["finalize_started_uptime"]
    return (upload["finalize_token"] is not None and started is not None
            and uptime - started < FINALIZE_STALE_SECONDS)


def finalizing(db, match_id: str, uptime: float) -> bool:
    """True while a verification that holds a token and started less than FINALIZE_STALE_SECONDS of server uptime
    before `uptime` is turning an upload of the match into a video (still_verifying, in SQL)."""
    return db.execute(
        "SELECT 1 FROM uploads WHERE match_id=? AND status='FINALIZING' AND finalize_token IS NOT NULL"
        " AND ? - finalize_started_uptime < ?",
        (match_id, uptime, FINALIZE_STALE_SECONDS)).fetchone() is not None


def delete_match_rows(db, files: MediaFiles, match_id: str) -> Removed:
    """Delete a match with its uploads, videos and analyses in the caller's write transaction.

    The caller checks `finalizing` first and unlinks the returned paths before its commit: a deletion
    must never leave a file behind. If the commit then fails, the rows return without their bytes
    and the same deletion clears them.
    """
    uploads = db.execute("SELECT id FROM uploads WHERE match_id=?", (match_id,)).fetchall()
    videos = db.execute("SELECT * FROM videos WHERE match_id=?", (match_id,)).fetchall()
    pending = [row["id"] for row in db.execute(
        "SELECT id FROM analysis_jobs WHERE match_id=? AND state IN (?,?)", (match_id, *ACTIVE_STATES))]
    # The order follows the foreign keys: videos first (their jobs, rallies, cards and decisions go
    # with them, ON DELETE CASCADE), then the uploads, then the match.
    db.execute("DELETE FROM videos WHERE match_id=?", (match_id,))
    db.execute("DELETE FROM uploads WHERE match_id=?", (match_id,))
    db.execute("DELETE FROM matches WHERE id=?", (match_id,))
    paths = [files.video(video) for video in videos]
    for upload in uploads:
        paths += [files.upload(upload["id"]), files.old_part(upload["id"])]
    return Removed(paths, pending)


def delete_team_rows(db, team_id: str) -> None:
    """Delete a team in the caller's write transaction (design 4.6, step 4).

    The team row goes; its seat pool, its memberships and the pool's blocks go by cascade, so the caller's
    connection has foreign keys on (db.connector). The logo lives in the team row and goes with it. The caller
    empties the write-ahead log after its commit. B7 adds the team's match records (steps 1-3) here.
    """
    db.execute("DELETE FROM teams WHERE id=?", (team_id,))


def move_captaincy(db, team_id: str, old_captain: str, new_captain: str, moment: str) -> None:
    """Make `new_captain`, a member of the team, its captain in the caller's write transaction.

    The old captain becomes a member first, because the schema allows one captain per team. Nobody's seat or
    jersey moves. The new captain's mark for the new-member badge is `moment`: the members who joined before it
    are not new to them.
    """
    db.execute("UPDATE memberships SET role='MEMBER' WHERE team_id=? AND user_id=?", (team_id, old_captain))
    db.execute("UPDATE memberships SET role='CAPTAIN', joins_seen_at=? WHERE team_id=? AND user_id=?",
               (moment, team_id, new_captain))


def hand_over_or_delete_teams(db, user_id: str, moment: str) -> None:
    """The team step of an account deletion, in its write transaction (design 3.7, step 2).

    Every team the account captains goes to its earliest-joined other member (joined_at, then the order of the
    rows), even when that member already captains as many teams as the captain limit allows (design 4.1): a team
    never stays without a captain. A team without another member is deleted. The account's memberships and blocks
    go by cascade with its user row afterwards. B7 adds the refusal CAPTAIN_MUST_TRANSFER and the rule for team
    videos here.
    """
    captained = [row["team_id"] for row in db.execute(
        "SELECT team_id FROM memberships WHERE user_id=? AND role='CAPTAIN' ORDER BY joined_at, rowid", (user_id,))]
    for team_id in captained:
        heir = db.execute("SELECT user_id FROM memberships WHERE team_id=? AND user_id<>? ORDER BY joined_at, rowid"
                          " LIMIT 1", (team_id, user_id)).fetchone()
        if heir is None:
            delete_team_rows(db, team_id)
        else:
            move_captaincy(db, team_id, user_id, heir["user_id"], moment)
