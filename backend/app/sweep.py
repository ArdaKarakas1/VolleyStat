"""The minute sweep: a background thread that closes what nobody finished (design 6.5, 8.7).

B3 has one step: an unfinished upload that took no chunk for a day of server uptime is aborted and its
bytes are deleted, and so is a verification that started a day of server uptime ago and never ended.
Only the time this server runs counts: a phone can not send a chunk to a server that is off, and the
owner's computer, which is the server, is switched off every night. It may also sleep instead: the
process then keeps running, but no pass comes until the computer wakes, and no phone can reach it.

The server counts its uptime itself and never reads the wall clock for it. server_clock.uptime holds the
seconds this server has run, summed over its life. The sweeper keeps in memory the uptime it wrote last and
the monotonic clock (time.monotonic) at that write. The uptime now is that value plus the monotonic seconds
since that write, when they are at most MAX_PASS_GAP_SECONDS, and that value alone when there are more:
such a gap means the computer slept (on a computer whose monotonic clock counts a sleep) or the process
stalled, and it counts as no uptime. Every pass and the stop write the uptime now. A start reads the stored
uptime and takes a fresh monotonic reading, so the time the server was off adds nothing. A wall clock that
is wrong, jumps or resets at every boot changes nothing.

Every touch carries the uptime of its moment: initiate, the repeated start of the same upload, every
stored chunk and every reopen of an upload (bytes lost on the disk, a checksum mismatch, a verification
that failed) write uploads.touched_uptime, and complete writes finalize_started_uptime with its FINALIZING
mark (main.py). The routes read the uptime from the running sweeper (app.state.sweeper) inside their write
transaction. Without a sweeper that read the stored uptime (a test client without threads, an operator
command, or the moment before the start read it) they use the stored uptime. The sweep closes an upload
once the uptime now is a day past its touch, and a verification once it is a day past its start.

complete, DELETE of the upload or of its match and the deletion of its account treat a verification as
abandoned once the uptime now is 15 minutes past its start (deletion.FINALIZE_STALE_SECONDS), and at once when
its row holds no token. A start frees every verification an earlier server process left: the storage lock
(storagelock.py) is taken before the start, so no other process can still run a verification in this folder,
and resume() clears their tokens in its write transaction. start() calls resume() before the lifespan lets the
server answer a request. A request of the earlier process that still runs (a forced stop lets the lock go
while a request checks its file) finds its token gone and writes nothing (main.py, still_ours). The tokens of
this process start with its mark (Sweeper.token), so a start that reads the stored uptime late, on the thread,
never frees a verification of its own. The wall clock moments touched_at and finalize_started_at stay in the
rows as information: no decision reads their value, and only an empty touch or start (both columns of it NULL)
marks a row stored before 0.9.

An upload never goes before the server counted a day of uptime after its last touch, and the server never
counts more uptime than it ran, with one exception: on a computer whose monotonic clock counts a sleep, a
nap that leaves at most MAX_PASS_GAP_SECONDS between two writes counts as uptime, and the upload can go early
by at most the sum of such short naps after its last touch. The counter misses uptime, and an upload goes that
much later than its day of uptime, in these cases: a crash or a power cut loses the uptime since the last
write (a pass comes every minute); on a computer whose monotonic clock counts a sleep, a sleep loses the
running seconds between the last write before it and the first write after it; a pass that comes more than
MAX_PASS_GAP_SECONDS after the last write (the thread waited for the database or the disk) loses that whole
gap; and the time between the start of the process and the moment the sweep read the stored uptime is lost.
A touch written in a gap that then counts as no uptime was written with the uptime before the gap plus the
seconds into the gap, so it stands ahead of the counter and its upload goes later, never earlier. The sweep
closes an upload at its next pass after the day ended: usually within a minute, and at most
MAX_PASS_GAP_SECONDS of uptime after it, because a pass that comes that long after the last write still
counts the whole gap.

A server that never wrote its uptime starts at 0. An upload stored before server 0.9 has no touch at all
(touched_at and touched_uptime NULL), and the first pass aborts it: its match belonged to no account, so no
phone could resume it, and app 0.9 starts an upload the server closed again from zero. An unfinished
upload written by an earlier build of B3 has touched_at but no touched_uptime: db.migrate gives it, and a
verification without finalize_started_uptime, the stored uptime of the first start of this build, so each
gets a full day of uptime from there. Such a verification is abandoned at once: server 0.8 wrote no token,
and the start frees the token of an earlier build. Later slices add their steps (requests, attempts, links,
open matches) to run_once.
"""
from __future__ import annotations

import logging
import secrets
import threading
import time
from typing import Callable

from .deletion import MediaFiles

log = logging.getLogger(__name__)

# An upload that took no chunk for this long, and a verification that started this long ago, is closed.
# Both count only the time the server runs.
IDLE_UPLOAD_SECONDS = 24 * 3600
SWEEP_SECONDS = 60.0
# The longest gap between two writes of the uptime that still counts as uptime. A running server writes it at
# every pass, a minute apart. A pass comes later when its two write transactions waited for the database (up
# to 30 s each) or a step waited for the disk, so three intervals cover a slow pass. A longer gap means that
# no pass could run: the computer slept, or the process stalled. It counts as no uptime, so an upload goes a
# little later. A nap shorter than this counts as uptime where the monotonic clock counts a sleep: such naps
# after the last touch add up, and an upload can go early by their sum. A pass that comes this long after the
# last write still counts the whole gap, so the sweep can close an upload up to this much uptime after its day.
MAX_PASS_GAP_SECONDS = 3 * SWEEP_SECONDS
STOP_JOIN_SECONDS = 5.0
THREAD_NAME = "volleystat-sweeper"

# An upload is idle once the uptime is a day past its last touch, a verification once it is a day past its
# start. A row without any touch was stored before server 0.9. The status term matches the partial index
# uploads_unfinished (db.py), so a pass reads only the unfinished rows.
_IDLE = ("status IN ('UPLOADING', 'FINALIZING') AND ("
         "(status = 'UPLOADING' AND ((touched_uptime IS NULL AND touched_at IS NULL)"
         " OR :uptime - touched_uptime >= :idle))"
         " OR (status = 'FINALIZING' AND ((finalize_started_uptime IS NULL AND finalize_started_at IS NULL)"
         " OR :uptime - finalize_started_uptime >= :idle)))")

_WRITE = ("INSERT INTO server_clock(id, uptime) VALUES(1, :uptime)"
          " ON CONFLICT(id) DO UPDATE SET uptime = excluded.uptime")

# The start frees every verification whose token another server process wrote: the storage lock is held, so that
# process has ended. A token of this process starts with its mark (:mine) and stays.
_FREE = ("UPDATE uploads SET finalize_token = NULL WHERE status = 'FINALIZING' AND finalize_token IS NOT NULL"
         " AND substr(finalize_token, 1, length(:mine)) != :mine")


def stored_uptime(db) -> float:
    """The uptime the server wrote last (server_clock), or 0 for a server that never wrote it."""
    row = db.execute("SELECT uptime FROM server_clock WHERE id = 1").fetchone()
    return float(row[0]) if row is not None else 0.0


class Sweeper:
    """Reads the stored uptime and frees the verifications of earlier processes when it starts, then runs
    run_once() at once and every SWEEP_SECONDS, on a daemon thread. `monotonic` is the clock it counts uptime
    with; a test hands in one it moves. One sweeper stands for one run of the server: a test that plays a new
    process makes a new sweeper."""

    def __init__(self, connect, files: MediaFiles, monotonic: Callable[[], float] = time.monotonic):
        self._connect = connect
        self._files = files
        self._monotonic = monotonic
        # The uptime this process wrote last and the monotonic reading at that write, under the lock. None
        # until the start read the stored uptime, and again after the stop: the routes then use the stored one.
        self._lock = threading.Lock()
        self._counted: float | None = None
        self._reading = 0.0
        self._stopping = threading.Event()
        self._thread: threading.Thread | None = None
        self._steps: tuple[tuple[str, Callable[[], int]], ...] = (("uploads", self.abort_idle_uploads),)
        # Every verification token of this run starts with this mark (token()), so that a start, early or late,
        # frees only the verifications that earlier processes left.
        self._mark = secrets.token_hex(8) + "."

    def token(self) -> str:
        """A new verification token of this run: complete writes it with its FINALIZING mark (main.py)."""
        return self._mark + secrets.token_hex(16)

    def start(self) -> None:
        """Read the stored uptime and free the verifications that earlier processes left (resume), then start the
        thread. The lifespan calls this before the server answers a request, so the first request finds them free.
        If the read fails, the thread tries again before each pass until it succeeds."""
        self._stopping.clear()
        self._resume_or_log()
        self._thread = threading.Thread(target=self._loop, name=THREAD_NAME, daemon=True)
        self._thread.start()

    def stop(self) -> None:
        """Stop the thread and write the uptime since the last pass, so the next start counts it."""
        self._stopping.set()
        if self._thread is not None:
            self._thread.join(STOP_JOIN_SECONDS)
            self._thread = None
        with self._lock:
            resumed = self._counted is not None
        if resumed:
            try:
                self.mark_alive()
            except Exception:
                log.exception("The sweep could not write its uptime at the stop")
            with self._lock:
                self._counted = None

    def _loop(self) -> None:
        while not self._stopping.is_set():
            with self._lock:
                resumed = self._counted is not None
            if resumed or self._resume_or_log():
                self.run_once()
            self._stopping.wait(SWEEP_SECONDS)

    def _resume_or_log(self) -> bool:
        try:
            self.resume()
            return True
        except Exception:
            log.exception("The sweep could not read its uptime; it sweeps nothing until it can")
            return False

    def _uptime_locked(self, reading: float) -> float:
        gap = reading - self._reading
        return self._counted + (gap if 0 <= gap <= MAX_PASS_GAP_SECONDS else 0.0)

    def uptime_now(self, db=None) -> float:
        """The seconds this server has run, now. Before the start read the stored uptime, and after the stop,
        the stored uptime, read through `db` (the caller's transaction) when it is given."""
        with self._lock:
            if self._counted is not None:
                return self._uptime_locked(self._monotonic())
        if db is not None:
            return stored_uptime(db)
        with self._connect() as own:
            return stored_uptime(own)

    def resume(self) -> float:
        """The start: read the stored uptime and free every verification an earlier process left (_FREE) in one
        write transaction, then take a fresh monotonic reading. The time the server was off adds nothing. start()
        calls it, and the thread again before a pass while it failed; a test calls it at each start it plays.
        Returns the stored uptime."""
        with self._connect(write=True) as db:
            counted = stored_uptime(db)
            freed = db.execute(_FREE, {"mine": self._mark}).rowcount
        if freed:
            log.info("The start freed %d verification(s) that earlier runs of the server left", freed)
        with self._lock:
            self._counted, self._reading = counted, self._monotonic()
        return counted

    def mark_alive(self) -> float:
        """The write of a pass and of the stop: add the monotonic seconds since the last write when they are at
        most MAX_PASS_GAP_SECONDS, and write the uptime. It reads the monotonic clock once it holds the write
        lock, and moves the uptime in memory in the same moment, so no route writes a touch in between.
        Returns the uptime it wrote."""
        with self._connect(write=True) as db:
            with self._lock:
                if self._counted is None:
                    raise RuntimeError("The sweep has not read the stored uptime")
                reading = self._monotonic()
                counted = self._uptime_locked(reading)
                self._counted, self._reading = counted, reading
            db.execute(_WRITE, {"uptime": counted})
        return counted

    def run_once(self) -> dict[str, int]:
        """Write the uptime, then run every step once, each in its own write transaction. A step that fails
        is logged and the next one still runs. Returns how many rows each step closed; a step that failed
        is missing. A pass that could not write the uptime runs no step and returns {}."""
        try:
            self.mark_alive()
        except Exception:
            log.exception("The sweep could not write its uptime; this pass closes nothing")
            return {}
        closed: dict[str, int] = {}
        for name, step in self._steps:
            try:
                closed[name] = step()
            except Exception:
                log.exception("The sweep step %s failed", name)
        return closed

    def abort_idle_uploads(self) -> int:
        """Abort every upload idle for a day of server uptime and delete its bytes before the commit. The
        rows stay (ABORTED), as after DELETE /v1/uploads/{id}, so the phone learns why its upload ended."""
        aborted = 0
        with self._connect(write=True) as db:
            values = {"uptime": self.uptime_now(db), "idle": IDLE_UPLOAD_SECONDS}
            for row in db.execute(f"SELECT id FROM uploads WHERE {_IDLE}", values).fetchall():
                db.execute("UPDATE uploads SET status='ABORTED', finalize_started_at=NULL, finalize_started_uptime=NULL,"
                           " finalize_token=NULL WHERE id=?", (row["id"],))
                self._files.upload(row["id"]).unlink(missing_ok=True)
                self._files.old_part(row["id"]).unlink(missing_ok=True)
                aborted += 1
        return aborted
