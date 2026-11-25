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
time off too: the computer slept, or the clock jumped forward. A pass and a stop also cut every time off
at the moment they write, since a time off can not end later than now: the server wrote one that does
while its clock showed a later moment than it shows now. The same holds for the touch times: a start, a
pass and a stop move a touch or the start of a verification that lies after now back to now. Such an
upload then gets a day of uptime from that write, which comes after its real touch, so it never goes
early. A dead verification can be taken over 15 minutes after that write, not only 15 minutes after the
clock reaches its start. A verification that still runs keeps its request: complete owns it by a token
(main.py, still_ours), and the moved start only decides when another complete may take it over and when
the sweep closes it.

A clock that reads earlier than the last write (wrong at a start, or set back while the server runs)
records no time off, and the write moves last_alive back with it. The server can not tell which of the two
readings is wrong, so that write keeps last_alive (server_clock.kept_alive), the time off it is about to
cut (server_downtimes_kept) and the touch times it is about to move (uploads.kept_*). A later start, or a
pass that records a gap, that finds the clock at or past the kept moment takes the step back for the wrong
reading: it puts all of that back, records the time off from the kept moment instead of the moment the
wrong clock wrote, and moves every touch written while the clock may have read behind to now. That covers
the computer whose clock resets to 2009 at every boot until NTP corrects it a minute later: without it the
correction recorded the time off from 2009 and every upload got a new day at every boot. A time off
recorded first, or a clock that reaches the kept moment without one, takes the step back for right and
forgets the kept moment: uptime then counts by the clock again. Either way an upload stays longer by at
most the time the clock went back, never shorter, and still goes. A second step back before either forgets
the first one and keeps its own.

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

_ALIVE = ("INSERT INTO server_clock(id, last_alive) VALUES(1, :now)"
          " ON CONFLICT(id) DO UPDATE SET last_alive = excluded.last_alive")
_DOWN = "INSERT INTO server_downtimes(stopped_at, started_at) VALUES(?, ?)"
_UNFINISHED = "status IN ('UPLOADING', 'FINALIZING')"

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
_CLAMP = (f"UPDATE uploads SET touched_at = :now WHERE {_UNFINISHED} AND touched_at > :now",
          "UPDATE uploads SET finalize_started_at = :now WHERE status = 'FINALIZING' AND finalize_started_at > :now")

# A clock write that reads an earlier moment than last_alive can not tell which of the two readings is wrong.
# It keeps last_alive, and everything that its _CUT and _CLAMP are about to change, before they change it.
_KEEP = ("INSERT INTO server_downtimes_kept(stopped_at, started_at)"
         " SELECT stopped_at, started_at FROM server_downtimes WHERE started_at >= :now",
         "UPDATE uploads SET kept_touched_at = touched_at, kept_finalize_started_at = finalize_started_at"
         f" WHERE {_UNFINISHED} AND (touched_at > :now OR finalize_started_at > :now)",
         "UPDATE server_clock SET kept_alive = last_alive, went_back_to = :now, behind_from = :floor WHERE id = 1")
_FORGET_KEPT = ("DELETE FROM server_downtimes_kept",
                "UPDATE uploads SET kept_touched_at = NULL, kept_finalize_started_at = NULL"
                " WHERE kept_touched_at IS NOT NULL OR kept_finalize_started_at IS NOT NULL",
                "UPDATE server_clock SET kept_alive = NULL, went_back_to = NULL, behind_from = NULL WHERE id = 1")


def _forget_kept(db) -> None:
    for statement in _FORGET_KEPT:
        db.execute(statement)


def _keep(db, moment: float, floor: float) -> None:
    """The clock reads `moment`, earlier than last_alive: keep last_alive and what the clock write changes."""
    _forget_kept(db)
    for statement in _KEEP:
        db.execute(statement, {"now": moment, "floor": floor})


def _put_back(db, clock, moment: float) -> None:
    """The clock reads `moment` after a time off, at or past the kept moment: the step back was the wrong
    reading. Put back the time off and the touch times that the step back changed, record the time off from
    the kept moment, and move every touch that may carry a moment the clock read behind to `moment`: the
    server can not tell when such a touch came, only that it came before now."""
    behind, floor, kept = clock["went_back_to"], clock["behind_from"], clock["kept_alive"]
    # Every time off that starts at or after the step back was kept as it was before the cut, and nothing
    # recorded a time off since (a recorded one forgets the kept moment).
    db.execute("DELETE FROM server_downtimes WHERE started_at >= ?", (behind,))
    db.execute("INSERT INTO server_downtimes(stopped_at, started_at) SELECT stopped_at, started_at FROM server_downtimes_kept")
    for upload in db.execute("SELECT id, touched_at, finalize_started_at, kept_touched_at, kept_finalize_started_at"
                             f" FROM uploads WHERE {_UNFINISHED}").fetchall():
        for column in ("touched_at", "finalize_started_at"):
            value, before = upload[column], upload["kept_" + column]
            if before is not None and before > behind and value == behind:
                # The step back moved it, and nothing wrote it since.
                value = before
            elif value is not None and floor <= value <= kept:
                value = moment
            else:
                continue
            db.execute(f"UPDATE uploads SET {column} = ? WHERE id = ?", (value, upload["id"]))
    _forget_kept(db)
    db.execute(_DOWN, (kept, moment))


def _read_clock(db, moment: float, longest_uptime: float, floor: float) -> None:
    """What a clock write does with the moment before it, before it cuts and moves anything. The time since
    last_alive is time off when it is longer than `longest_uptime`. `floor` is the earliest moment a touch
    written while the clock read behind can carry, if `moment` is a step back."""
    clock = db.execute("SELECT * FROM server_clock WHERE id = 1").fetchone()
    if clock is None:
        return
    gap = moment - clock["last_alive"] > longest_uptime
    if moment < clock["last_alive"]:
        # A time off can not end before it began: nothing is recorded. A kept moment of an earlier step back
        # is forgotten, and this step back is kept instead.
        _keep(db, moment, floor)
        return
    kept = clock["kept_alive"]
    if kept is not None and gap and moment >= kept:
        _put_back(db, clock, moment)
        return
    if kept is not None and (gap or moment >= kept):
        # The clock reached the kept moment, or a time off came before it did: the step back was right.
        _forget_kept(db)
    if gap:
        db.execute(_DOWN, (clock["last_alive"], moment))


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
        # The clock when this process was born. A touch written before the start reads the clock may carry
        # any moment from this one on.
        self._born = time.time()
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
        """Record the time this server was off before this start (`now`) and move every touch after `now` back
        to it, in one write transaction. A clock behind the stop keeps what the start changes (_keep); a start
        that reaches a kept moment puts that back (_put_back). The thread calls it once, before its first pass;
        a test calls it at each start it plays."""
        moment = time.time() if now is None else now
        with self._connect(write=True) as db:
            if db.execute("SELECT 1 FROM server_clock WHERE id = 1").fetchone() is None:
                db.execute(_DOWN, (0.0, moment))
            else:
                # Every moment from the stop to the start is time off. A clock that reads earlier than the
                # stop records nothing, and the first pass cuts the time off that the earlier clock leaves
                # in the future.
                _read_clock(db, moment, 0.0, min(moment - MAX_PASS_GAP_SECONDS, self._born))
            for statement in _CLAMP:
                db.execute(statement, {"now": moment})
            db.execute(_FORGET, {"now": moment})
            db.execute(_ALIVE, {"now": moment})
        self._resumed = True

    def mark_alive(self, now: float | None = None) -> float:
        """The clock write of a pass and of a stop, in one write transaction: cut every time off at `now`,
        move every touch and every start of a verification that lies after `now` back to `now`, record a
        gap since the last write that is longer than MAX_PASS_GAP_SECONDS as time off, forget what no
        unfinished upload needs, and write `now` as the moment this server was last seen running. A clock
        that went back records nothing, keeps what this write changes (_keep) and moves last_alive back with
        it. A gap that reaches the kept moment puts that back (_put_back). Without `now` it reads the clock
        once it holds the write lock. Returns the moment it wrote."""
        with self._connect(write=True) as db:
            moment = time.time() if now is None else now
            # A touch written between a step back and this write carries a moment after the step, which lies
            # at most one slow pass interval before this one.
            _read_clock(db, moment, MAX_PASS_GAP_SECONDS, moment - MAX_PASS_GAP_SECONDS)
            for statement in (*_CUT, *_CLAMP):
                db.execute(statement, {"now": moment})
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
                if db.execute(f"UPDATE uploads SET status='ABORTED', finalize_started_at=NULL, finalize_token=NULL"
                              f" WHERE id=:id AND {_IDLE}",
                              {**cut, "id": row["id"]}).rowcount:
                    self._files.upload(row["id"]).unlink(missing_ok=True)
                    self._files.old_part(row["id"]).unlink(missing_ok=True)
                    aborted += 1
        return aborted
