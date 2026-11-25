"""Where the bytes of uploads and videos live, and what deleting a match removes.

The routes, the analysis worker, the upload sweep, account deletion and the operator's legacy
command share these, so every deletion removes the same rows and the same files.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .jobs import ACTIVE_STATES

# A verification that started this many seconds of server uptime ago (sweep.py) is treated as abandoned
# (crashed process) and may be taken over by the next complete call. A younger one holds its match
# against deletion.
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
    """True while the FINALIZING upload's verification started less than FINALIZE_STALE_SECONDS of server uptime
    before `uptime`. A row without the uptime of its start (stored before server 0.9) is abandoned."""
    started = upload["finalize_started_uptime"]
    return started is not None and uptime - started < FINALIZE_STALE_SECONDS


def finalizing(db, match_id: str, uptime: float) -> bool:
    """True while a verification that started less than FINALIZE_STALE_SECONDS of server uptime before `uptime`
    is turning an upload of the match into a video."""
    return db.execute(
        "SELECT 1 FROM uploads WHERE match_id=? AND status='FINALIZING' AND ? - finalize_started_uptime < ?",
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
