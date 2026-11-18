"""The minute sweep: a background thread that closes what nobody finished (design 6.5, 8.7).

B3 has one step: an unfinished upload that took no chunk for a day is aborted and its bytes are
deleted, and so is a verification that started a day ago and never ended. Later slices add their
steps (requests, attempts, links, open matches) to run_once.
"""
from __future__ import annotations

import logging
import threading
import time
from typing import Callable

from .deletion import MediaFiles

log = logging.getLogger(__name__)

# An upload that took no chunk for this long, and a verification that started this long ago, is closed.
IDLE_UPLOAD_SECONDS = 24 * 3600
SWEEP_SECONDS = 60.0
STOP_JOIN_SECONDS = 5.0
THREAD_NAME = "volleystat-sweeper"

# The same condition in the SELECT and in each UPDATE: a chunk that committed between the two has
# moved touched_at, and the UPDATE then leaves its upload alone.
_IDLE = ("((status = 'UPLOADING' AND (touched_at IS NULL OR touched_at <= :cut))"
         " OR (status = 'FINALIZING' AND COALESCE(finalize_started_at, 0) <= :cut))")


class Sweeper:
    """Runs run_once() when it starts and then every SWEEP_SECONDS, on a daemon thread."""

    def __init__(self, connect, files: MediaFiles):
        self._connect = connect
        self._files = files
        self._stopping = threading.Event()
        self._thread: threading.Thread | None = None
        self._steps: tuple[tuple[str, Callable[[float], int]], ...] = (("uploads", self.abort_idle_uploads),)

    def start(self) -> None:
        self._stopping.clear()
        self._thread = threading.Thread(target=self._loop, name=THREAD_NAME, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stopping.set()
        if self._thread is not None:
            self._thread.join(STOP_JOIN_SECONDS)
            self._thread = None

    def _loop(self) -> None:
        while not self._stopping.is_set():
            self.run_once()
            self._stopping.wait(SWEEP_SECONDS)

    def run_once(self, now: float | None = None) -> dict[str, int]:
        """Run every step once, each in its own write transaction. A step that fails is logged and the
        next one still runs. Returns how many rows each step closed; a step that failed is missing."""
        moment = time.time() if now is None else now
        closed: dict[str, int] = {}
        for name, step in self._steps:
            try:
                closed[name] = step(moment)
            except Exception:
                log.exception("The sweep step %s failed", name)
        return closed

    def abort_idle_uploads(self, now: float) -> int:
        """Abort every upload idle for a day and delete its bytes before the commit. The rows stay
        (ABORTED), as after DELETE /v1/uploads/{id}, so the phone learns why its upload ended."""
        cut = {"cut": now - IDLE_UPLOAD_SECONDS}
        aborted = 0
        with self._connect(write=True) as db:
            for row in db.execute(f"SELECT id FROM uploads WHERE {_IDLE}", cut).fetchall():
                if db.execute(f"UPDATE uploads SET status='ABORTED', finalize_started_at=NULL WHERE id=:id AND {_IDLE}",
                              {**cut, "id": row["id"]}).rowcount:
                    self._files.upload(row["id"]).unlink(missing_ok=True)
                    self._files.old_part(row["id"]).unlink(missing_ok=True)
                    aborted += 1
        return aborted
