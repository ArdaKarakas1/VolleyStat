"""The minute sweep: a background thread that closes what nobody finished (design 6.5, 8.7).

B3 has one step: an unfinished upload that took no chunk for a day of server uptime is aborted and its
bytes are deleted, and so is a verification that started a day of server uptime ago and never ended.
Only the time this server runs counts: a phone can not send a chunk to a server that is off, and the
owner's computer, which is the server, is switched off every night. It may also sleep instead: the
process then keeps running, but no pass comes until the computer wakes, and no phone can reach it.

The server keeps its own uptime clock in two tables. server_clock holds the last moment the server was
seen running: every pass writes it, and so does a stop. A start (resume) records the time from that
moment to the start in server_downtimes. The idle time of an upload is the time since its last touch
minus every time off after that touch. The time off is never added to the touch times, because the
15-minute takeover of a dead verification reads finalize_started_at as well. After a crash or a power
cut the last pass stands in for the stop, so the time off is about one sweep interval (a minute) longer
than it was: an upload then stays a little longer, never shorter.

A pass and a stop record a gap since the last clock write that is longer than MAX_PASS_GAP_SECONDS as
time off too: the computer slept, or the clock jumped forward. A clock write also cuts every time off at
the moment it writes, since a time off can not end later than now: the server wrote one that does while
its clock showed a later moment than it shows now. The same holds for the touch times: a clock write
moves a touch or the start of a verification that lies after now back to now. Such an upload then gets a
day of uptime from that write, which comes after its real touch, so it never goes early. A dead
verification can be taken over 15 minutes after that write, not only 15 minutes after the clock reaches
its start. A verification that still runs loses its mark this way: its request answers 409
UPLOAD_FINALIZING, and the next complete verifies the upload again 15 minutes after the write. A clock
that reads earlier than the last write (wrong at a start, or set back while the server runs)
records no time off, and the write moves last_alive back with it. Uptime then counts by the clock again:
an upload stays longer by at most the time the clock went back, never shorter, and still goes.

A clock write reads the clock once it holds the write lock. Every touch committed before that was written
with an earlier reading, so a right clock never finds a touch after now, and a chunk or a complete that
came while the write waited for the database keeps its moment.

A server that never wrote its clock (the first start of this version) can not tell how long it was off
before the start, so it counts all of that time as time off: every unfinished upload then gets a full
day. An upload stored before server 0.9 has no touch time, and the first pass aborts it: its match
belonged to no account, so no phone could resume it, and app 0.9 starts an upload the server closed
again from zero. Later slices add their steps (requests, attempts, links, open matches) to run_once.
"""
from __future__ import annotations

import logging
import threading
import time
from typing import Callable

from .deletion import MediaFiles

log = logging.getLogger(__name__)

# An upload that took no chunk for this long, and a verification that started this long ago, is closed.
# Both count only the time the server runs.
IDLE_UPLOAD_SECONDS = 24 * 3600
SWEEP_SECONDS = 60.0
# The longest gap between two clock writes that still counts as uptime. A running server writes its clock
# at every pass, a minute apart. A pass comes later when its two write transactions waited for the
# database (up to 30 s each) or a step waited for the disk, so three intervals cover a slow pass. A longer
# gap means that no pass could run: the computer slept, or the clock jumped forward. Recording a slow pass
# as time off would only keep an upload a little longer; a nap shorter than this counts as uptime, and an
# upload can then go that much early.
MAX_PASS_GAP_SECONDS = 3 * SWEEP_SECONDS
STOP_JOIN_SECONDS = 5.0
THREAD_NAME = "volleystat-sweeper"


def _time_off_after(column: str) -> str:
    """SQL for the seconds this server was off after the moment in uploads.<column>."""
    return (f"(SELECT COALESCE(SUM(started_at - MAX(stopped_at, uploads.{column})), 0) FROM server_downtimes"
            f" WHERE started_at > uploads.{column})")


# The same condition in the SELECT and in each UPDATE: a chunk that committed between the two has
# moved touched_at, and the UPDATE then leaves its upload alone. A row is idle when its last touch,
# moved forward by the time the server was off after it, lies a day back.
_IDLE = (f"((status = 'UPLOADING' AND (touched_at IS NULL"
         f" OR touched_at + {_time_off_after('touched_at')} <= :cut))"
         f" OR (status = 'FINALIZING' AND (finalize_started_at IS NULL"
         f" OR finalize_started_at + {_time_off_after('finalize_started_at')} <= :cut)))")

# A time off that ended before the oldest touch of every unfinished upload can never count again:
# every later touch comes after it. Without unfinished uploads no time off is needed at all.
_FORGET = ("DELETE FROM server_downtimes WHERE started_at <= COALESCE((SELECT MIN(moment) FROM ("
           "SELECT touched_at AS moment FROM uploads WHERE status IN ('UPLOADING', 'FINALIZING')"
           " UNION ALL SELECT finalize_started_at FROM uploads WHERE status = 'FINALIZING')), :now)")

_ALIVE = "INSERT OR REPLACE INTO server_clock(id, last_alive) VALUES(1, :now)"
_DOWN = "INSERT INTO server_downtimes(stopped_at, started_at) VALUES(?, ?)"

# A time off can not end after now. The server wrote one that does while its clock showed a later moment
# than it shows now (the clock ran ahead, or it was set back since): it would give every upload touched
# before it that much more time, and _FORGET would never delete it. The clock write cuts it at now, and
# deletes a time off that then ends before it began.
_CUT = ("UPDATE server_downtimes SET started_at = :now WHERE started_at > :now",
        "DELETE FROM server_downtimes WHERE stopped_at >= started_at")

# A touch or the start of a verification that lies after now: the server wrote it while its clock showed a
# later moment than it shows now. Left as it is, it would hold the upload, its quota and its disk
# reservation until the clock reached it (_IDLE), and complete, DELETE of the upload, DELETE of its match
# and the deletion of its account would answer UPLOAD_FINALIZING until 15 minutes after it (main.py,
# deletion.finalizing). The clock write moves it back to now: the sweep then closes the upload a day of
# uptime later, and those routes treat a dead verification as stale 15 minutes later.
_CLAMP = ("UPDATE uploads SET touched_at = :now WHERE status IN ('UPLOADING', 'FINALIZING') AND touched_at > :now",
          "UPDATE uploads SET finalize_started_at = :now WHERE status = 'FINALIZING' AND finalize_started_at > :now")


class Sweeper:
    """Records the time off when it starts, then runs run_once() at once and every SWEEP_SECONDS, on a
    daemon thread."""

    def __init__(self, connect, files: MediaFiles):
        self._connect = connect
        self._files = files
        # True once this process recorded the time off before its start. Until then it neither
        # sweeps nor writes its clock. Only a start records the whole time off: a pass records only a
        # gap longer than MAX_PASS_GAP_SECONDS, and nothing when the server never wrote its clock.
        self._resumed = False
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
        if self._resumed:
            # The next start then counts the time off from this moment and not from the last pass.
            try:
                self.mark_alive()
            except Exception:
                log.exception("The sweep could not write the server clock at the stop")

    def _loop(self) -> None:
        while not self._stopping.is_set():
            if not self._resumed:
                try:
                    self.resume()
                except Exception:
                    log.exception("The sweep could not read the server clock; it sweeps nothing until it can")
            if self._resumed:
                self.run_once()
            self._stopping.wait(SWEEP_SECONDS)

    def resume(self, now: float | None = None) -> None:
        """Record the time this server was off before this start (`now`), in one write transaction. The
        thread calls it once, before its first pass; a test calls it at each start it plays."""
        moment = time.time() if now is None else now
        with self._connect(write=True) as db:
            row = db.execute("SELECT last_alive FROM server_clock WHERE id = 1").fetchone()
            stopped = 0.0 if row is None else row["last_alive"]
            # A clock that reads earlier than the last write records nothing: a time off can not end
            # before it began. The first pass cuts the time off and moves the touch times that the
            # earlier clock leaves in the future.
            if stopped < moment:
                db.execute(_DOWN, (stopped, moment))
            db.execute(_FORGET, {"now": moment})
            db.execute(_ALIVE, {"now": moment})
        self._resumed = True

    def mark_alive(self, now: float | None = None) -> float:
        """The clock write of a pass and of a stop, in one write transaction: cut every time off at `now`,
        move every touch and every start of a verification that lies after `now` back to `now`, record a
        gap since the last write that is longer than MAX_PASS_GAP_SECONDS as time off, forget what no
        unfinished upload needs, and write `now` as the moment this server was last seen running. A clock
        that went back records nothing and moves last_alive back with it. Without `now` it reads the clock
        once it holds the write lock. Returns the moment it wrote."""
        with self._connect(write=True) as db:
            moment = time.time() if now is None else now
            row = db.execute("SELECT last_alive FROM server_clock WHERE id = 1").fetchone()
            for statement in (*_CUT, *_CLAMP):
                db.execute(statement, {"now": moment})
            if row is not None and moment - row["last_alive"] > MAX_PASS_GAP_SECONDS:
                db.execute(_DOWN, (row["last_alive"], moment))
            db.execute(_FORGET, {"now": moment})
            db.execute(_ALIVE, {"now": moment})
        return moment

    def run_once(self, now: float | None = None) -> dict[str, int]:
        """Write the clock, then run every step once, each in its own write transaction. A step that fails
        is logged and the next one still runs. Returns how many rows each step closed; a step that failed
        is missing. A pass that could not write the clock runs no step and returns {}. Every step uses the
        moment of the clock write."""
        try:
            moment = self.mark_alive(now)
        except Exception:
            # This pass could not record a sleep before it, so it closes nothing. The next clock write
            # records the whole gap since the last one.
            log.exception("The sweep could not write the server clock; this pass closes nothing")
            return {}
        closed: dict[str, int] = {}
        for name, step in self._steps:
            try:
                closed[name] = step(moment)
            except Exception:
                log.exception("The sweep step %s failed", name)
        return closed

    def abort_idle_uploads(self, now: float) -> int:
        """Abort every upload idle for a day of server uptime and delete its bytes before the commit. The
        rows stay (ABORTED), as after DELETE /v1/uploads/{id}, so the phone learns why its upload ended."""
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
