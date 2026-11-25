"""The seats of a pool: the starter and substitute seats of a team (design 2.3; docs/API_V1.md, section 10).

A team always has 6 starter seats and 0 to 6 substitute seats. The schema fixes the starter count and keeps the
substitute count at 0 or more (seat_pools, db.py); the upper bound of the substitute count lives here and in the
creation route, so a later owner decision on it needs no rebuild of the table (S9 of the design's v5 checkers).

B4 has no join, so no seat is offered or held while somebody decides, and the engine checks the holders only.
B5 adds the queue to this module: pause recovery, settle, the PoolKind adapter that reads an open match's
players, and the refusal SEATS_IN_USE (plan U33 and U34).
"""
from __future__ import annotations

from typing import Iterable, Protocol

from .errors import fail

STARTER, SUBSTITUTE = "STARTER", "SUBSTITUTE"
SEAT_TYPES = (STARTER, SUBSTITUTE)


class Move(Protocol):
    userId: str
    seat: str


def check_substitute_count(count: int, upper: int) -> None:
    """422 SEATS_INVALID (SUBSTITUTES_RANGE) for a substitute count outside 0..upper. Nothing is read or written."""
    if not 0 <= count <= upper:
        fail("SEATS_INVALID", "The substitute seats are outside the allowed range", 422, reason="SUBSTITUTES_RANGE")


def seats_of_holders(db, pool) -> dict[str, str]:
    """user id -> the seat type each holder of the pool holds (B4: the members of the pool's team)."""
    return {row["user_id"]: row["seat"] for row in db.execute(
        "SELECT user_id, seat FROM memberships WHERE team_id=? ORDER BY joined_at, rowid", (pool["team_id"],))}


def holders_by_type(db, pool) -> dict[str, int]:
    """The number of holders of each seat type. B5 reads them from the pool_holders view instead."""
    counts = {seat: 0 for seat in SEAT_TYPES}
    for seat in seats_of_holders(db, pool).values():
        counts[seat] += 1
    return counts


def change_seats(db, pool, substitute_seats: int, moves: Iterable[Move], upper: int) -> None:
    """Set the substitute count of the pool and move holders between the two types, checked on the final state.

    Runs inside the caller's write transaction. Nothing is written before every check passes:
    1. the count lies in 0..upper (422 SEATS_INVALID, SUBSTITUTES_RANGE);
    2. every moved user holds a seat of the pool (422 NOT_A_TEAM_MEMBER, details.userId);
    3. after the moves the holders of each type fit its seats, the starter type first (409 SEATS_BELOW_HOLDERS
       with details.seat, details.holders and details.seats). The starter count never changes, so only moves
       can fail it.
    Then only memberships.seat of the moved holders and seat_pools.substitute_seats change: never a roster row
    or any other column (S1). A move to the seat a holder already holds, and the count the pool already has,
    write nothing, so the same body twice changes nothing.
    """
    check_substitute_count(substitute_seats, upper)
    current = seats_of_holders(db, pool)
    final = dict(current)
    for move in moves:
        if move.userId not in current:
            fail("NOT_A_TEAM_MEMBER", "This account is not a member of the team", 422, userId=move.userId)
        final[move.userId] = move.seat
    seats = {STARTER: pool["starter_seats"], SUBSTITUTE: substitute_seats}
    for seat in SEAT_TYPES:
        holders = sum(1 for held in final.values() if held == seat)
        if holders > seats[seat]:
            fail("SEATS_BELOW_HOLDERS", "More holders than seats of this type", 409,
                 seat=seat, holders=holders, seats=seats[seat])
    for user_id, seat in final.items():
        if seat != current[user_id]:
            db.execute("UPDATE memberships SET seat=? WHERE team_id=? AND user_id=?", (seat, pool["team_id"], user_id))
    if substitute_seats != pool["substitute_seats"]:
        db.execute("UPDATE seat_pools SET substitute_seats=? WHERE id=?", (substitute_seats, pool["id"]))
