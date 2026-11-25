"""Teams: creation, reading, settings and seats (docs/API_V1.md, section 10, Takımlar; design v5.1 sections 4.1,
4.3, 4.4 and 9.3; ADR-0120).

Every signed-in account may create a team (v5, R6). A team always has 6 starter seats and 0 to 6 substitute
seats, 2 by default, and its captain holds one of them. The server draws the team's tag at random. A person is a
member of at most 20 teams and the captain of at most 3; both limits are read from the database. An account creates
at most 3 teams a day; that count lives in the limiter in process memory and starts again when the server restarts
(design 3.6). The join link, the queue and the invite come with B5, so `invite` is null and nobody is deciding or
waiting yet.
"""
from __future__ import annotations

import secrets
import unicodedata
from pathlib import Path
from typing import Any, Callable, Literal

from fastapi import APIRouter, Depends, Response
from pydantic import BaseModel, ConfigDict, Field, StrictInt, model_validator

from . import access, accounts, seats
from .db import is_canonical_id, new_id
from .errors import fail

# The owner's numbers (design 16.1 items 11, 15, 22 and 33). A change is a change of these constants: the schema
# fixes only the 6 starter seats.
MEMBER_LIMIT = 20
CAPTAIN_LIMIT = 3
CREATIONS_PER_DAY = 3
STARTERS = 6
SUBSTITUTES_MAX = 6
DEFAULT_SUBSTITUTES = 2

NAME_LENGTH = (2, 40)
TAG_LENGTH = 4
# A tag is drawn again while another team holds it. With 31^4 tags a hundred misses in a row do not happen.
TAG_DRAWS = 100
DAY_SECONDS = 24 * 3600

Seat = Literal["STARTER", "SUBSTITUTE"]


# --- what the captain gives --------------------------------------------------------------------------------

def clean_team_name(text: str) -> str | None:
    """The team name as stored, or None when it breaks the rule (design 3.1; plan U9).

    NFC, no control or format character (Cc or Cf: tabs, line breaks, bidi and zero-width marks), runs of
    whitespace made one space, trimmed, then 2 to 40 characters with at least one letter or digit. Any other
    character may stand in a name: punctuation and symbols too.
    """
    text = unicodedata.normalize("NFC", text)
    if any(unicodedata.category(char) in ("Cc", "Cf") for char in text):
        return None
    text = " ".join(text.split())
    if not NAME_LENGTH[0] <= len(text) <= NAME_LENGTH[1]:
        return None
    if not any(char.isalnum() for char in text):
        return None
    return text


class Schedule(BaseModel):
    """The team's usual match day and time: 1 for Monday to 7 for Sunday, and HH:MM."""
    model_config = ConfigDict(extra="forbid")

    weekday: StrictInt = Field(ge=1, le=7)
    time: str = Field(pattern=r"^([01][0-9]|2[0-3]):[0-5][0-9]$")


class TeamInput(BaseModel):
    # No starter count: a team always has 6 starter seats, so a body with starterSeats is refused (design 2.3).
    # The substitute count has no bound here; its range answers SEATS_INVALID from the code.
    model_config = ConfigDict(extra="forbid")

    name: str = Field(max_length=200)
    substituteSeats: StrictInt = DEFAULT_SUBSTITUTES
    captainSeat: Seat = "STARTER"
    schedule: Schedule | None = None
    jersey: StrictInt | None = Field(default=None, ge=1, le=99)


class SettingsInput(BaseModel):
    # Both settings are stated: a missing schedule is refused, and null clears it.
    model_config = ConfigDict(extra="forbid")

    name: str = Field(max_length=200)
    schedule: Schedule | None


class Move(BaseModel):
    model_config = ConfigDict(extra="forbid")

    userId: str = Field(max_length=64)
    seat: Seat


class SeatsInput(BaseModel):
    """The final state the captain wants: the substitute count and the members who change their seat type."""
    model_config = ConfigDict(extra="forbid")

    substituteSeats: StrictInt
    moves: list[Move] = Field(default_factory=list, max_length=12)

    @model_validator(mode="after")
    def each_member_once(self):
        users = [move.userId for move in self.moves]
        if len(set(users)) != len(users):
            raise ValueError("A body names each member at most once")
        return self


# --- the tag -------------------------------------------------------------------------------------------------

def _random_tag() -> str:
    return "".join(secrets.choice(accounts.CODE_ALPHABET) for _ in range(TAG_LENGTH))


def draw_tag(db) -> str:
    """A random tag no team holds, drawn inside the creating transaction (design 4.4). The team id never enters it."""
    for _ in range(TAG_DRAWS):
        tag = _random_tag()
        if db.execute("SELECT 1 FROM teams WHERE tag=?", (tag,)).fetchone() is None:
            return tag
    raise RuntimeError("No free team tag was drawn")


# --- what a member sees --------------------------------------------------------------------------------------

def _seat_count(seats_of_type: int, taken: int) -> dict[str, int]:
    # Nobody decides on a seat before B5 brings the join.
    return {"seats": seats_of_type, "taken": taken, "left": seats_of_type - taken, "deciding": 0}


def _new_joins(members, captain) -> int:
    """Members other than the captain who joined after the captain's mark (design 4.3)."""
    mark = captain["joins_seen_at"]
    return sum(1 for member in members
               if member["user_id"] != captain["user_id"] and (mark is None or member["joined_at"] > mark))


def team_payload(db, team_id: str, caller_id: str) -> dict[str, Any]:
    """The Team shape (design 9.3, B4 form) as the caller sees it. The caller is a member of the team.

    `newJoins` and `seats.waiting` are for the captain only and null for everyone else. `invite` is null until B5.
    The logo is named by its version only, never by its bytes.
    """
    team = db.execute(
        "SELECT t.id, t.name, t.tag, t.match_weekday, t.match_time, t.logo IS NOT NULL AS has_logo, t.logo_version,"
        " p.id AS pool_id, p.team_id, p.starter_seats, p.substitute_seats"
        " FROM teams t JOIN seat_pools p ON p.team_id = t.id WHERE t.id=?", (team_id,)).fetchone()
    members = db.execute(
        "SELECT m.user_id, m.role, m.seat, m.jersey, m.joined_at, m.joins_seen_at, u.display_name"
        " FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.team_id=? ORDER BY m.joined_at, m.rowid",
        (team_id,)).fetchall()
    mine = next(member for member in members if member["user_id"] == caller_id)
    captain = mine["role"] == "CAPTAIN"
    taken = seats.holders_by_type(db, team)
    return {
        "id": team["id"], "poolId": team["pool_id"], "name": team["name"], "tag": team["tag"],
        "schedule": ({"weekday": team["match_weekday"], "time": team["match_time"]}
                     if team["match_weekday"] is not None else None),
        "logo": {"version": team["logo_version"]} if team["has_logo"] else None,
        "myRole": mine["role"], "mySeat": mine["seat"], "myJersey": mine["jersey"],
        "newJoins": _new_joins(members, mine) if captain else None,
        "members": [{"userId": member["user_id"], "displayName": member["display_name"], "jersey": member["jersey"],
                     "role": member["role"], "seat": member["seat"], "joinedAt": member["joined_at"]}
                    for member in members],
        "seats": {"starter": _seat_count(team["starter_seats"], taken[seats.STARTER]),
                  "substitute": _seat_count(team["substitute_seats"], taken[seats.SUBSTITUTE]),
                  "waiting": 0 if captain else None},
        "invite": None,
    }


def teams_of(db, user_id: str) -> list[dict[str, Any]]:
    """The Team of every team the account is a member of, in the order the account joined them."""
    return [team_payload(db, row["team_id"], user_id) for row in db.execute(
        "SELECT team_id FROM memberships WHERE user_id=? ORDER BY joined_at, rowid", (user_id,))]


# --- the routes ----------------------------------------------------------------------------------------------

def router(connect, db_path: Path, current_user: Callable[..., accounts.Caller],
           limiter: accounts.Limiter) -> APIRouter:
    """The team routes. `limiter` is the app's one limiter, which also counts the logins. `db_path` is for the
    deletions of the members part, which empty the write-ahead log after they commit."""
    routes = APIRouter()

    @routes.get("/v1/teams")
    def list_teams(caller: accounts.Caller = Depends(current_user)):
        """The teams of the caller, in the order the caller joined them."""
        with connect() as db:
            return {"teams": teams_of(db, caller.id)}

    @routes.put("/v1/teams/{team_id}", responses={201: {"description": "The team was created under this id"}})
    def put_team(team_id: str, payload: TeamInput, response: Response,
                 caller: accounts.Caller = Depends(current_user)):
        """Create a team under an id the client chose, or return the caller's team that already has it.

        The server draws the tag, creates the team's seat pool (6 starter seats and the substitute seats) and makes
        the caller its captain on the seat the body names. A repeat by a member answers 200 and changes nothing;
        another account gets 409 TEAM_ID_TAKEN. A refused body writes nothing and takes no place of the day.
        """
        if not is_canonical_id(team_id):
            fail("TEAM_ID_INVALID", "Team id must be a UUID in lowercase with dashes", 422)
        name = clean_team_name(payload.name)
        if name is None:
            fail("NAME_INVALID", "The team name does not meet the rule", 422)
        seats.check_substitute_count(payload.substituteSeats, SUBSTITUTES_MAX)
        if payload.captainSeat == seats.SUBSTITUTE and payload.substituteSeats == 0:
            fail("SEATS_INVALID", "The captain's seat type has no seat", 422, reason="CAPTAIN_SEAT")
        key = f"team:user:{caller.id}"
        reserved_at = None
        try:
            with connect(write=True) as db:
                access.session_user(db, caller)
                if db.execute("SELECT 1 FROM teams WHERE id=?", (team_id,)).fetchone() is not None:
                    if db.execute("SELECT 1 FROM memberships WHERE team_id=? AND user_id=?",
                                  (team_id, caller.id)).fetchone() is None:
                        # No name, tag or member of that team reaches another account.
                        fail("TEAM_ID_TAKEN", "Another account's team has this id", 409)
                    return team_payload(db, team_id, caller.id)
                counts = db.execute("SELECT COUNT(*) AS memberships, COALESCE(SUM(role = 'CAPTAIN'), 0) AS captaincies"
                                    " FROM memberships WHERE user_id=?", (caller.id,)).fetchone()
                if counts["memberships"] >= MEMBER_LIMIT:
                    fail("TEAM_LIMIT_REACHED", "This account is a member of the most teams it may join", 409,
                         scope="MEMBER", limit=MEMBER_LIMIT)
                if counts["captaincies"] >= CAPTAIN_LIMIT:
                    fail("TEAM_LIMIT_REACHED", "This account captains the most teams it may captain", 409,
                         scope="CAPTAIN", limit=CAPTAIN_LIMIT)
                # Read inside the write lock, so the creations of one account count in the order they commit.
                moment = accounts.now()
                at = moment.timestamp()
                # Last of the checks: only a creation that passes every other check takes a place of the day.
                wait = limiter.reserve(key, CREATIONS_PER_DAY, DAY_SECONDS, "TEAMS", at)
                if wait is not None:
                    accounts.too_many(wait)
                reserved_at = at
                created = accounts.iso(moment)
                schedule = payload.schedule
                db.execute("INSERT INTO teams(id, name, tag, match_weekday, match_time, created_at)"
                           " VALUES(?,?,?,?,?,?)", (team_id, name, draw_tag(db), schedule.weekday if schedule else None,
                                                    schedule.time if schedule else None, created))
                db.execute("INSERT INTO seat_pools(id, kind, team_id, starter_seats, substitute_seats, created_at)"
                           " VALUES(?,?,?,?,?,?)",
                           (new_id(), "TEAM", team_id, STARTERS, payload.substituteSeats, created))
                # The captain has seen every join so far: nobody else has joined.
                db.execute("INSERT INTO memberships(team_id, user_id, role, seat, jersey, joined_at, joins_seen_at)"
                           " VALUES(?,?,?,?,?,?,?)",
                           (team_id, caller.id, "CAPTAIN", payload.captainSeat, payload.jersey, created, created))
                response.status_code = 201
                return team_payload(db, team_id, caller.id)
        except BaseException:
            # A creation that ended without a team gives its place of the day back.
            if reserved_at is not None:
                limiter.release(key, reserved_at)
            raise

    @routes.get("/v1/teams/{team_id}")
    def get_team(team_id: str, caller: accounts.Caller = Depends(current_user)):
        """The team as a member sees it."""
        with connect() as db:
            access.require_member(db, caller, team_id)
            return team_payload(db, team_id, caller.id)

    @routes.put("/v1/teams/{team_id}/settings")
    def put_settings(team_id: str, payload: SettingsInput, caller: accounts.Caller = Depends(current_user)):
        """Set the team's name and usual match day and time (captain). A null schedule clears it."""
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require_captain(db, caller, team_id)
            name = clean_team_name(payload.name)
            if name is None:
                fail("NAME_INVALID", "The team name does not meet the rule", 422)
            schedule = payload.schedule
            db.execute("UPDATE teams SET name=?, match_weekday=?, match_time=? WHERE id=?",
                       (name, schedule.weekday if schedule else None, schedule.time if schedule else None, team_id))
            return team_payload(db, team_id, caller.id)

    @routes.put("/v1/teams/{team_id}/seats")
    def put_seats(team_id: str, payload: SeatsInput, caller: accounts.Caller = Depends(current_user)):
        """Set the substitute count and move members between starter and substitute seats (captain).

        The server checks the final state: the same body twice changes nothing. The 6 starter seats never change.
        """
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require_captain(db, caller, team_id)
            pool = db.execute("SELECT * FROM seat_pools WHERE team_id=?", (team_id,)).fetchone()
            seats.change_seats(db, pool, payload.substituteSeats, payload.moves, SUBSTITUTES_MAX)
            return team_payload(db, team_id, caller.id)

    return routes
