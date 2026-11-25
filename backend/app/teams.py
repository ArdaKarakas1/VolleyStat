"""Teams: creation, reading, settings, seats, members, logos and deletion (docs/API_V1.md, section 10, Takımlar;
design v5.1 sections 3.7, 4.1, 4.2, 4.3, 4.4, 4.6 and 9.3; ADR-0120).

Every signed-in account may create a team (v5, R6). A team always has 6 starter seats and 0 to 6 substitute
seats, 2 by default, and its captain holds one of them. The server draws the team's tag at random. A person is a
member of at most 20 teams and the captain of at most 3; both limits are read from the database. An account creates
at most 3 teams a day; that count lives in the limiter in process memory and starts again when the server restarts
(design 3.6). The join link, the queue and the invite come with B5, so `invite` is null and nobody is deciding or
waiting yet.

A member sets their own jersey and leaves by themselves; the captain sets anyone's jersey, removes members, which
blocks the removed account in the team's pool, lifts blocks, moves the new-member mark, hands the captaincy on and
deletes the team. A captain alone in the team deletes it by leaving (plan U8); a captain with other members hands
the captaincy on first. B5 reads the blocks when a removed account tries to join again.

The captain sets and removes the team's logo, two lossy WebP images that pass the header check of logo.py, and every
member reads them. The bytes live in the team row; the Team shape names the logo by its version only.
"""
from __future__ import annotations

import base64
import secrets
import sqlite3
import unicodedata
from pathlib import Path
from typing import Any, Callable, Literal

from fastapi import APIRouter, Depends, Header, Response
from pydantic import BaseModel, ConfigDict, Field, StrictInt, field_validator, model_validator

from . import access, accounts, deletion, logo, seats
from .db import empty_write_ahead_log, is_canonical_id, new_id
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


class JerseyInput(BaseModel):
    # The body states the jersey: null clears it, and a missing jersey is refused.
    model_config = ConfigDict(extra="forbid")

    jersey: StrictInt | None = Field(ge=1, le=99)


class JoinsSeenInput(BaseModel):
    """The moment up to which the captain has seen the members who joined, as the team screen showed them."""
    model_config = ConfigDict(extra="forbid")

    # ASCII digits only: the pattern of the server's own moments (accounts.iso).
    upTo: str = Field(pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$")

    @field_validator("upTo")
    @classmethod
    def a_real_moment(cls, value: str) -> str:
        accounts.parse_time(value)  # ValueError for a month 13 or a 30 February
        return value


class CaptainInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    userId: str = Field(max_length=64)


class LogoInput(BaseModel):
    """Both images of a logo in base64: at most 256 px for members and at most 128 px for link views (design 4.2)."""
    model_config = ConfigDict(extra="forbid")

    # Base64 of 36 KiB is 49 152 characters and of 8 KiB 10 924. The bounds leave a little room and keep a body of
    # both under the 64 KiB body limit; the decoded bytes have their own limits (logo.SIZES).
    large: str = Field(max_length=50_000)
    small: str = Field(max_length=11_000)


# --- the logo ------------------------------------------------------------------------------------------------

def logo_image(text: str, image: str) -> bytes:
    """The bytes of one image of a logo, or 422 LOGO_INVALID that names the image (`large` or `small`).

    Base64 with any character outside the standard alphabet, a line break included, or without its padding is
    refused as a file that breaks the rule is.
    """
    max_side, max_bytes = logo.SIZES[image]
    try:
        data = base64.b64decode(text, validate=True)
        logo.check_lossy_webp(data, max_side, max_bytes)
    except ValueError as refused:  # binascii.Error is a ValueError
        fail("LOGO_INVALID", f"The {image} logo is not a simple lossy WebP within its limits: {refused}", 422,
             image=image)
    return data


def holds_tag(if_none_match: str | None, tag: str) -> bool:
    """Whether an If-None-Match header names `tag`: by weak comparison, so W/"3" holds "3", in a list, or as * for
    any logo (RFC 9110, section 13.1.2)."""
    if if_none_match is None:
        return False
    for candidate in if_none_match.split(","):
        candidate = candidate.strip()
        if candidate == "*" or candidate.removeprefix("W/") == tag:
            return True
    return False


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


def _member(row) -> dict[str, Any]:
    return {"userId": row["user_id"], "displayName": row["display_name"], "jersey": row["jersey"], "role": row["role"],
            "seat": row["seat"], "joinedAt": row["joined_at"]}


def member_payload(db, team_id: str, user_id: str) -> dict[str, Any]:
    """One member of the team, as the Team shape lists it."""
    return _member(db.execute(
        "SELECT m.user_id, m.role, m.seat, m.jersey, m.joined_at, u.display_name"
        " FROM memberships m JOIN users u ON u.id = m.user_id WHERE m.team_id=? AND m.user_id=?",
        (team_id, user_id)).fetchone())


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
        "members": [_member(member) for member in members],
        "seats": {"starter": _seat_count(team["starter_seats"], taken[seats.STARTER]),
                  "substitute": _seat_count(team["substitute_seats"], taken[seats.SUBSTITUTE]),
                  "waiting": 0 if captain else None},
        "invite": None,
    }


def teams_of(db, user_id: str) -> list[dict[str, Any]]:
    """The Team of every team the account is a member of, in the order the account joined them."""
    return [team_payload(db, row["team_id"], user_id) for row in db.execute(
        "SELECT team_id FROM memberships WHERE user_id=? ORDER BY joined_at, rowid", (user_id,))]


# --- members -------------------------------------------------------------------------------------------------

def membership(db, team_id: str, user_id: str) -> sqlite3.Row | None:
    """The membership of the account in the team, or None."""
    return db.execute("SELECT * FROM memberships WHERE team_id=? AND user_id=?", (team_id, user_id)).fetchone()


def member_of(db, team_id: str, user_id: str) -> sqlite3.Row:
    """The membership a member route names in its path, or 404 MEMBER_NOT_FOUND. The caller is a member of the
    team already, so the answer tells them nothing the member list does not show."""
    found = membership(db, team_id, user_id)
    if found is None:
        fail("MEMBER_NOT_FOUND", "This account is not a member of the team", 404)
    return found


def jersey_holder(db, team_id: str, jersey: int, user_id: str) -> str | None:
    """The other member of the team who wears `jersey`, or None."""
    row = db.execute("SELECT user_id FROM memberships WHERE team_id=? AND jersey=? AND user_id<>?",
                     (team_id, jersey, user_id)).fetchone()
    return row["user_id"] if row is not None else None


def jersey_taken(jersey: int) -> None:
    fail("JERSEY_TAKEN", "Another member of the team wears this jersey", 409, jersey=jersey)


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
                try:
                    created = accounts.iso(moment)
                    schedule = payload.schedule
                    db.execute("INSERT INTO teams(id, name, tag, match_weekday, match_time, created_at)"
                               " VALUES(?,?,?,?,?,?)", (team_id, name, draw_tag(db),
                                                        schedule.weekday if schedule else None,
                                                        schedule.time if schedule else None, created))
                    db.execute("INSERT INTO seat_pools(id, kind, team_id, starter_seats, substitute_seats, created_at)"
                               " VALUES(?,?,?,?,?,?)",
                               (new_id(), "TEAM", team_id, STARTERS, payload.substituteSeats, created))
                    # The captain has seen every join so far: nobody else has joined.
                    db.execute("INSERT INTO memberships(team_id, user_id, role, seat, jersey, joined_at,"
                               " joins_seen_at) VALUES(?,?,?,?,?,?,?)",
                               (team_id, caller.id, "CAPTAIN", payload.captainSeat, payload.jersey, created, created))
                    response.status_code = 201
                    return team_payload(db, team_id, caller.id)
                except BaseException:
                    # The place goes back while this transaction still holds the write lock. A creation of the
                    # same account that waits for the lock then counts only the places of creations that count.
                    limiter.release(key, reserved_at)
                    reserved_at = None
                    raise
        except BaseException:
            # Only a failed COMMIT reaches this with a place still taken. Its rollback has already freed the lock,
            # so the place goes back a moment later than above.
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

    @routes.put("/v1/teams/{team_id}/members/{user_id}/jersey")
    def put_jersey(team_id: str, user_id: str, payload: JerseyInput, caller: accounts.Caller = Depends(current_user)):
        """Set or clear a member's jersey: a member's own, or anyone's by the captain. A jersey is unique among the
        members of one team."""
        with connect(write=True) as db:
            access.session_user(db, caller)
            _team, mine = access.require_member(db, caller, team_id)
            member_of(db, team_id, user_id)
            if user_id != caller.id:
                access.refuse_unless_captain(mine)
            jersey = payload.jersey
            if jersey is not None and jersey_holder(db, team_id, jersey, user_id) is not None:
                jersey_taken(jersey)
            try:
                db.execute("UPDATE memberships SET jersey=? WHERE team_id=? AND user_id=?", (jersey, team_id, user_id))
            except sqlite3.IntegrityError as refused:
                # The unique index answers as the check does, should a write ever pass the check by.
                if "memberships.jersey" not in str(refused):
                    raise
                jersey_taken(jersey)
            return member_payload(db, team_id, user_id)

    @routes.delete("/v1/teams/{team_id}/members/{user_id}", status_code=204)
    def delete_member(team_id: str, user_id: str, caller: accounts.Caller = Depends(current_user)):
        """Leave the team (the caller's own id) or remove a member (captain).

        A removal blocks the removed account in the team's pool in the same transaction; leaving writes no block.
        Either frees the member's seat with its type. A captain with other members hands the captaincy on first
        (409 CAPTAIN_MUST_TRANSFER); a captain alone deletes the team by leaving.
        """
        team_deleted = False
        with connect(write=True) as db:
            access.session_user(db, caller)
            _team, mine = access.require_member(db, caller, team_id)
            target = member_of(db, team_id, user_id)
            if user_id != caller.id:
                access.refuse_unless_captain(mine)
            if target["role"] == "CAPTAIN":
                # Only the captain's own leave reaches this: a team has one captain.
                if db.execute("SELECT 1 FROM memberships WHERE team_id=? AND user_id<>?",
                              (team_id, user_id)).fetchone() is not None:
                    fail("CAPTAIN_MUST_TRANSFER", "The captain hands the captaincy on before leaving", 409)
                deletion.delete_team_rows(db, team_id)
                team_deleted = True
            else:
                db.execute("DELETE FROM memberships WHERE team_id=? AND user_id=?", (team_id, user_id))
                if user_id != caller.id:
                    db.execute("INSERT INTO pool_blocks(pool_id, user_id, created_at)"
                               " SELECT id, ?, ? FROM seat_pools WHERE team_id=?"
                               " ON CONFLICT(pool_id, user_id) DO UPDATE SET created_at=excluded.created_at",
                               (user_id, accounts.iso(accounts.now()), team_id))
        if team_deleted:
            empty_write_ahead_log(db_path)

    @routes.get("/v1/teams/{team_id}/blocks")
    def list_blocks(team_id: str, caller: accounts.Caller = Depends(current_user)):
        """The accounts the captain removed from the team, in the order of their removal (captain)."""
        with connect() as db:
            access.require_captain(db, caller, team_id)
            return {"blocked": [
                {"userId": row["user_id"], "displayName": row["display_name"], "blockedAt": row["created_at"]}
                for row in db.execute(
                    "SELECT b.user_id, u.display_name, b.created_at FROM pool_blocks b"
                    " JOIN seat_pools p ON p.id = b.pool_id JOIN users u ON u.id = b.user_id"
                    " WHERE p.team_id=? ORDER BY b.created_at, b.rowid", (team_id,))]}

    @routes.delete("/v1/teams/{team_id}/blocks/{user_id}", status_code=204)
    def delete_block(team_id: str, user_id: str, caller: accounts.Caller = Depends(current_user)):
        """Lift the block of a removed account (captain). A block that does not exist answers the same."""
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require_captain(db, caller, team_id)
            db.execute("DELETE FROM pool_blocks WHERE user_id=? AND pool_id=(SELECT id FROM seat_pools WHERE team_id=?)",
                       (user_id, team_id))

    @routes.post("/v1/teams/{team_id}/joins-seen", status_code=204)
    def joins_seen(team_id: str, payload: JoinsSeenInput, caller: accounts.Caller = Depends(current_user)):
        """Move the captain's mark for the new-member badge to `upTo` (captain).

        The mark never moves back, and never past the server's time, so a member who joins later still counts as
        new.
        """
        with connect(write=True) as db:
            access.session_user(db, caller)
            _team, mine = access.require_captain(db, caller, team_id)
            seen = min(payload.upTo, accounts.iso(accounts.now()))
            mark = mine["joins_seen_at"]
            if mark is None or seen > mark:
                db.execute("UPDATE memberships SET joins_seen_at=? WHERE team_id=? AND user_id=?",
                           (seen, team_id, caller.id))

    @routes.post("/v1/teams/{team_id}/captain")
    def transfer_captaincy(team_id: str, payload: CaptainInput, caller: accounts.Caller = Depends(current_user)):
        """Hand the captaincy to another member (captain), who may captain at most CAPTAIN_LIMIT teams with it.

        Both role changes happen in one transaction and the seats stay. The answer is the team as the caller now
        sees it. A transfer to the caller changes nothing.
        """
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require_captain(db, caller, team_id)
            heir = payload.userId
            if membership(db, team_id, heir) is None:
                fail("NOT_A_TEAM_MEMBER", "This account is not a member of the team", 422, userId=heir)
            if heir != caller.id:
                captained = db.execute("SELECT COUNT(*) FROM memberships WHERE user_id=? AND role='CAPTAIN'",
                                       (heir,)).fetchone()[0]
                if captained >= CAPTAIN_LIMIT:
                    fail("TEAM_LIMIT_REACHED", "This member captains the most teams they may captain", 409,
                         scope="CAPTAIN", limit=CAPTAIN_LIMIT, userId=heir)
                deletion.move_captaincy(db, team_id, caller.id, heir, accounts.iso(accounts.now()))
            return team_payload(db, team_id, caller.id)

    @routes.put("/v1/teams/{team_id}/logo")
    def put_logo(team_id: str, payload: LogoInput, caller: accounts.Caller = Depends(current_user)):
        """Set both images of the team's logo (captain).

        Each image is a simple lossy WebP: at most 256 px and 36 KiB for members, at most 128 px and 8 KiB for link
        views. The first image that breaks the rule is named in 422 LOGO_INVALID, and nothing is written. A change
        moves the logo's version; the same bytes again change nothing. A replaced logo leaves no bytes behind: the
        rows are overwritten with zeros (secure_delete) and the write-ahead log is emptied after the commit.
        """
        replaced = False
        with connect(write=True) as db:
            access.session_user(db, caller)
            team, _mine = access.require_captain(db, caller, team_id)
            large = logo_image(payload.large, "large")
            small = logo_image(payload.small, "small")
            version = team["logo_version"]
            if (team["logo"], team["logo_small"]) != (large, small):
                replaced = team["logo"] is not None or team["logo_small"] is not None
                version += 1
                db.execute("UPDATE teams SET logo=?, logo_small=?, logo_version=? WHERE id=?",
                           (large, small, version, team_id))
        if replaced:
            empty_write_ahead_log(db_path)
        return {"logo": {"version": version}}

    @routes.delete("/v1/teams/{team_id}/logo", status_code=204)
    def delete_logo(team_id: str, caller: accounts.Caller = Depends(current_user)):
        """Remove both images of the team's logo (captain) and move its version. Without a logo nothing changes."""
        removed = False
        with connect(write=True) as db:
            access.session_user(db, caller)
            team, _mine = access.require_captain(db, caller, team_id)
            if team["logo"] is not None or team["logo_small"] is not None:
                db.execute("UPDATE teams SET logo=NULL, logo_small=NULL, logo_version=logo_version + 1 WHERE id=?",
                           (team_id,))
                removed = True
        if removed:
            empty_write_ahead_log(db_path)

    @routes.get("/v1/teams/{team_id}/logo", response_class=Response, responses={
        200: {"description": "The image of the logo", "content": {"image/webp": {}}},
        304: {"description": "If-None-Match names the logo's current version"},
    })
    def get_logo(team_id: str, size: Literal["large", "small"],
                 if_none_match: str | None = Header(default=None, alias="If-None-Match"),
                 caller: accounts.Caller = Depends(current_user)):
        """One image of the team's logo (member). The ETag is the logo's version, so a phone that holds it gets 304."""
        with connect() as db:
            team, _mine = access.require_member(db, caller, team_id)
        data = team["logo"] if size == "large" else team["logo_small"]
        if data is None:
            fail("LOGO_NOT_FOUND", "The team has no logo", 404)
        tag = f'"{team["logo_version"]}"'
        headers = {"ETag": tag, "Cache-Control": "private, no-cache"}
        if holds_tag(if_none_match, tag):
            return Response(status_code=304, headers=headers)
        return Response(content=data, media_type="image/webp", headers=headers)

    @routes.delete("/v1/teams/{team_id}", status_code=204)
    def delete_team(team_id: str, caller: accounts.Caller = Depends(current_user)):
        """Delete the team with its seat pool, memberships and blocks (captain). Nothing of it stays in the file."""
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require_captain(db, caller, team_id)
            deletion.delete_team_rows(db, team_id)
        empty_write_ahead_log(db_path)

    return routes
