"""The seats of a team: 6 starter seats always, 0 to 6 substitute seats, and the captain's moves (docs/API_V1.md,
section 10, Takımlar; design v5.1 sections 2.3, 4.1, 4.3 and 8.2).

B4 has no join and no queue, so the engine checks the holders only (plan U33). Members are seeded straight into
SQLite (tests.helpers.seed_member). Every name below is made up by the tests.
"""
import sqlite3
import uuid
from contextlib import closing

import pytest

from app import accounts
from app.db import new_id
from tests.helpers import as_account, create_team, rows, seed_member, user_id_of
from tests.test_access import refused_as, snapshot
from tests.test_api import create_match


def seats_of(client, team_id, substitutes, moves=()):
    return client.put(f"/v1/teams/{team_id}/seats",
                      json={"substituteSeats": substitutes, "moves": [{"userId": user, "seat": seat} for user, seat in moves]})


def holders(storage, team_id):
    return {row["user_id"]: row["seat"] for row in rows(storage, "SELECT user_id, seat FROM memberships WHERE team_id=?", team_id)}


def team_with(client, storage, starters=0, substitutes=0, substitute_seats=2, name="Kuzey Spor"):
    """A team of the caller with `starters` starter members and `substitutes` substitute members beside the captain,
    who holds a starter seat. Returns the team and the seeded user ids in seat order."""
    team = create_team(client, name=name, substituteSeats=substitute_seats)
    starting = [seed_member(storage, team["id"], f"test.as{index}") for index in range(starters)]
    waiting = [seed_member(storage, team["id"], f"test.yedek{index}", seat="SUBSTITUTE") for index in range(substitutes)]
    return team, starting, waiting


def insert(storage, statement, *parameters):
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
        db.execute("PRAGMA foreign_keys=ON")
        db.execute(statement, parameters)


def insert_pool(storage, kind, starters, substitutes, team_id=None, match_id=None):
    insert(storage, "INSERT INTO seat_pools(id, kind, team_id, match_id, starter_seats, substitute_seats, created_at)"
                    " VALUES(?,?,?,?,?,?,?)", new_id(), kind, team_id, match_id, starters, substitutes,
           accounts.iso(accounts.now()))


def insert_team(storage):
    team_id = new_id()
    insert(storage, "INSERT INTO teams(id, name, tag, created_at) VALUES(?,?,?,?)", team_id, "Elle Takım",
           team_id[:4].upper(), accounts.iso(accounts.now()))
    return team_id


# --- the substitute count ------------------------------------------------------------------------------

def test_a_substitute_count_outside_0_to_6_answers_seats_invalid_before_any_write(client, storage):
    team, _starting, _waiting = team_with(client, storage, starters=2, substitutes=1)
    before = snapshot(storage)
    for count in (-1, 7, 100):
        error = refused_as(seats_of(client, team["id"], count), 422, "SEATS_INVALID")
        assert error["details"] == {"reason": "SUBSTITUTES_RANGE"}
    for count in (7, -1):
        error = refused_as(client.put(f"/v1/teams/{uuid.uuid4()}", json={"name": "Güney Spor", "substituteSeats": count}),
                           422, "SEATS_INVALID")
        assert error["details"] == {"reason": "SUBSTITUTES_RANGE"}
    assert snapshot(storage) == before
    # Both ends of the range pass.
    assert create_team(client, name="Yedeksiz", substituteSeats=0)["seats"]["substitute"]["seats"] == 0
    assert seats_of(client, team["id"], 6).json()["seats"]["substitute"] == {"seats": 6, "taken": 1, "left": 5,
                                                                           "deciding": 0}


@pytest.mark.parametrize("count", ["2", 2.0, True, None])
def test_a_substitute_count_that_is_not_an_integer_answers_422(client, storage, count):
    team = create_team(client, name="Kuzey Spor")
    before = snapshot(storage)
    refused_as(seats_of(client, team["id"], count), 422, "VALIDATION_ERROR")
    refused_as(client.put(f"/v1/teams/{uuid.uuid4()}", json={"name": "Güney Spor", "substituteSeats": count}), 422,
               "VALIDATION_ERROR")
    assert snapshot(storage) == before


def test_a_body_with_starter_seats_answers_422_on_the_route_and_on_creation(client, storage):
    team = create_team(client, name="Kuzey Spor")
    before = snapshot(storage)
    for starters in (6, 5):
        refused_as(client.put(f"/v1/teams/{team['id']}/seats", json={"substituteSeats": 2, "starterSeats": starters}),
                   422, "VALIDATION_ERROR")
        refused_as(client.put(f"/v1/teams/{uuid.uuid4()}", json={"name": "Güney Spor", "starterSeats": starters}),
                   422, "VALIDATION_ERROR")
    assert snapshot(storage) == before


# --- the schema --------------------------------------------------------------------------------------

def test_the_schema_refuses_another_starter_count(client, storage):
    for starters in (5, 7, 12):
        with pytest.raises(sqlite3.IntegrityError):
            insert_pool(storage, "TEAM", starters, 2, team_id=insert_team(storage))
    match_id = create_match(client)
    for starters in (6, 13):
        with pytest.raises(sqlite3.IntegrityError):
            insert_pool(storage, "OPEN_MATCH", starters, 2, match_id=match_id)
    # The counts the design fixes pass: 6 for a team and 12 for an open match.
    insert_pool(storage, "TEAM", 6, 2, team_id=insert_team(storage))
    insert_pool(storage, "OPEN_MATCH", 12, 2, match_id=match_id)


def test_the_schema_refuses_a_negative_substitute_count(client, storage):
    with pytest.raises(sqlite3.IntegrityError):
        insert_pool(storage, "TEAM", 6, -1, team_id=insert_team(storage))
    team = create_team(client, name="Kuzey Spor")
    with pytest.raises(sqlite3.IntegrityError):
        insert(storage, "UPDATE seat_pools SET substitute_seats=-1 WHERE id=?", team["poolId"])


def test_the_schema_takes_a_substitute_count_above_the_route_bound(client, storage):
    # The bound of 6 lives in the code, so a later owner decision on it needs no rebuild of seat_pools (S9).
    insert_pool(storage, "TEAM", 6, 7, team_id=insert_team(storage))
    team = create_team(client, name="Kuzey Spor")
    insert(storage, "UPDATE seat_pools SET substitute_seats=12 WHERE id=?", team["poolId"])
    assert rows(storage, "SELECT substitute_seats FROM seat_pools WHERE id=?", team["poolId"]) == [{"substitute_seats": 12}]
    # The route still refuses 7.
    refused_as(seats_of(client, team["id"], 7), 422, "SEATS_INVALID")


def test_the_schema_refuses_a_pool_with_both_a_team_and_a_match_or_neither(client, storage):
    match_id = create_match(client)
    for kind, starters in (("TEAM", 6), ("OPEN_MATCH", 12)):
        with pytest.raises(sqlite3.IntegrityError):
            insert_pool(storage, kind, starters, 2, team_id=insert_team(storage), match_id=match_id)
        with pytest.raises(sqlite3.IntegrityError):
            insert_pool(storage, kind, starters, 2)
    # A pool names its own kind of owner only.
    with pytest.raises(sqlite3.IntegrityError):
        insert_pool(storage, "TEAM", 6, 2, match_id=match_id)
    with pytest.raises(sqlite3.IntegrityError):
        insert_pool(storage, "OPEN_MATCH", 12, 2, team_id=insert_team(storage))
    with pytest.raises(sqlite3.IntegrityError):
        insert_pool(storage, "LEAGUE", 6, 2, team_id=insert_team(storage))


def test_an_insert_into_memberships_without_a_seat_fails_on_the_schema(client, storage):
    team = create_team(client, name="Kuzey Spor")
    as_account(client, storage, "test.koltuksuz")
    stranger_id = user_id_of(storage, "test.koltuksuz")
    moment = accounts.iso(accounts.now())
    with pytest.raises(sqlite3.IntegrityError):
        insert(storage, "INSERT INTO memberships(team_id, user_id, role, joined_at) VALUES(?,?,?,?)",
               team["id"], stranger_id, "MEMBER", moment)
    for seat in ("LIBERO", "starter", ""):
        with pytest.raises(sqlite3.IntegrityError):
            insert(storage, "INSERT INTO memberships(team_id, user_id, role, seat, joined_at) VALUES(?,?,?,?,?)",
                   team["id"], stranger_id, "MEMBER", seat, moment)
    assert holders(storage, team["id"]) == {user_id_of(storage): "STARTER"}


# --- the captain's seat at creation ---------------------------------------------------------------------

def test_the_captain_holds_a_starter_seat_by_default_and_a_substitute_seat_on_request(client, storage):
    starting = create_team(client, name="Kuzey Spor")
    assert starting["mySeat"] == "STARTER" and starting["members"][0]["seat"] == "STARTER"
    assert (starting["seats"]["starter"]["taken"], starting["seats"]["substitute"]["taken"]) == (1, 0)
    waiting = create_team(client, name="Güney Spor", captainSeat="SUBSTITUTE", substituteSeats=1)
    assert waiting["mySeat"] == "SUBSTITUTE" and waiting["members"][0]["seat"] == "SUBSTITUTE"
    assert waiting["seats"]["starter"] == {"seats": 6, "taken": 0, "left": 6, "deciding": 0}
    assert waiting["seats"]["substitute"] == {"seats": 1, "taken": 1, "left": 0, "deciding": 0}
    assert holders(storage, waiting["id"]) == {user_id_of(storage): "SUBSTITUTE"}
    refused_as(client.put(f"/v1/teams/{uuid.uuid4()}", json={"name": "Batı Spor", "captainSeat": "LIBERO"}), 422,
               "VALIDATION_ERROR")


def test_captain_seat_substitute_with_0_substitute_seats_answers_captain_seat_and_creates_nothing(client, storage):
    before = snapshot(storage)
    refused = client.put(f"/v1/teams/{uuid.uuid4()}",
                         json={"name": "Kuzey Spor", "substituteSeats": 0, "captainSeat": "SUBSTITUTE"})
    assert refused_as(refused, 422, "SEATS_INVALID")["details"] == {"reason": "CAPTAIN_SEAT"}
    # The range comes first.
    refused = client.put(f"/v1/teams/{uuid.uuid4()}",
                         json={"name": "Kuzey Spor", "substituteSeats": -1, "captainSeat": "SUBSTITUTE"})
    assert refused_as(refused, 422, "SEATS_INVALID")["details"] == {"reason": "SUBSTITUTES_RANGE"}
    assert snapshot(storage) == before


# --- moves and counts --------------------------------------------------------------------------------------

def test_moves_and_counts_are_checked_on_the_final_state(client, storage):
    team, _starting, waiting = team_with(client, storage, starters=2, substitutes=2)
    before = snapshot(storage)
    # Lowering the count to 1 alone would leave 2 substitutes on 1 seat.
    error = refused_as(seats_of(client, team["id"], 1), 409, "SEATS_BELOW_HOLDERS")
    assert error["details"] == {"seat": "SUBSTITUTE", "holders": 2, "seats": 1}
    assert snapshot(storage) == before
    # Moved up to a free starter seat in the same call, one substitute fits the lower count.
    changed = seats_of(client, team["id"], 1, [(waiting[0], "STARTER")])
    assert changed.status_code == 200, changed.text
    assert changed.json()["seats"]["starter"] == {"seats": 6, "taken": 4, "left": 2, "deciding": 0}
    assert changed.json()["seats"]["substitute"] == {"seats": 1, "taken": 1, "left": 0, "deciding": 0}
    assert holders(storage, team["id"])[waiting[0]] == "STARTER"
    assert rows(storage, "SELECT substitute_seats FROM seat_pools WHERE id=?", team["poolId"]) == [{"substitute_seats": 1}]


def test_a_swap_in_one_call_passes(client, storage):
    team, starting, waiting = team_with(client, storage, starters=5, substitutes=1)
    # Six starters and one substitute: neither single move fits, the swap does.
    changed = seats_of(client, team["id"], 2, [(starting[0], "SUBSTITUTE"), (waiting[0], "STARTER")])
    assert changed.status_code == 200, changed.text
    seats = holders(storage, team["id"])
    assert (seats[starting[0]], seats[waiting[0]]) == ("SUBSTITUTE", "STARTER")
    assert [member["seat"] for member in changed.json()["members"] if member["userId"] in (starting[0], waiting[0])] == [
        "SUBSTITUTE", "STARTER"]
    assert changed.json()["seats"]["starter"]["taken"] == 6 and changed.json()["seats"]["substitute"]["taken"] == 1


def test_a_single_move_into_a_full_type_answers_seats_below_holders_and_changes_nothing(client, storage):
    team, starting, waiting = team_with(client, storage, starters=5, substitutes=2)
    before = snapshot(storage)
    error = refused_as(seats_of(client, team["id"], 2, [(waiting[0], "STARTER")]), 409, "SEATS_BELOW_HOLDERS")
    assert error["details"] == {"seat": "STARTER", "holders": 7, "seats": 6}
    error = refused_as(seats_of(client, team["id"], 2, [(starting[0], "SUBSTITUTE")]), 409, "SEATS_BELOW_HOLDERS")
    assert error["details"] == {"seat": "SUBSTITUTE", "holders": 3, "seats": 2}
    # The starter type is checked first.
    error = refused_as(seats_of(client, team["id"], 0, [(waiting[0], "STARTER")]), 409, "SEATS_BELOW_HOLDERS")
    assert error["details"] == {"seat": "STARTER", "holders": 7, "seats": 6}
    assert snapshot(storage) == before


def test_lowering_the_substitute_count_below_its_holders_answers_seats_below_holders(client, storage):
    team, _starting, _waiting = team_with(client, storage, substitutes=2, substitute_seats=3)
    before = snapshot(storage)
    for count in (1, 0):
        error = refused_as(seats_of(client, team["id"], count), 409, "SEATS_BELOW_HOLDERS")
        assert error["details"] == {"seat": "SUBSTITUTE", "holders": 2, "seats": count}
    assert snapshot(storage) == before
    lowered = seats_of(client, team["id"], 2)
    assert lowered.status_code == 200 and lowered.json()["seats"]["substitute"] == {
        "seats": 2, "taken": 2, "left": 0, "deciding": 0}


def test_a_move_of_someone_who_is_not_a_member_answers_not_a_team_member(client, storage, stranger):
    team, starting, _waiting = team_with(client, storage, starters=1)
    other = create_team(stranger, name="Güney Spor")
    outsider = user_id_of(storage, "test.yabanci")
    before = snapshot(storage)
    for user_id in (outsider, str(uuid.uuid4()), "kimse"):
        error = refused_as(seats_of(client, team["id"], 2, [(starting[0], "SUBSTITUTE"), (user_id, "STARTER")]), 422,
                           "NOT_A_TEAM_MEMBER")
        assert error["details"] == {"userId": user_id}
    # A member of another team is not a member of this one.
    refused_as(seats_of(client, team["id"], 2, [(other["members"][0]["userId"], "STARTER")]), 422, "NOT_A_TEAM_MEMBER")
    assert snapshot(storage) == before


def test_a_body_naming_one_member_twice_answers_422(client, storage):
    team, starting, _waiting = team_with(client, storage, starters=1)
    before = snapshot(storage)
    refused = seats_of(client, team["id"], 2, [(starting[0], "SUBSTITUTE"), (starting[0], "STARTER")])
    refused_as(refused, 422, "VALIDATION_ERROR")
    # At most 12 moves, and a move names a known seat type only.
    refused_as(seats_of(client, team["id"], 2, [(str(uuid.uuid4()), "STARTER") for _ in range(13)]), 422,
               "VALIDATION_ERROR")
    refused_as(seats_of(client, team["id"], 2, [(starting[0], "LIBERO")]), 422, "VALIDATION_ERROR")
    assert snapshot(storage) == before


def test_the_same_body_twice_changes_nothing_and_answers_200(client, storage):
    team, starting, waiting = team_with(client, storage, starters=2, substitutes=1)
    body = (4, [(starting[0], "SUBSTITUTE"), (waiting[0], "STARTER")])
    first = seats_of(client, team["id"], *body)
    assert first.status_code == 200, first.text
    after_first = snapshot(storage)
    again = seats_of(client, team["id"], *body)
    assert again.status_code == 200 and again.json() == first.json()
    assert snapshot(storage) == after_first
    # The state the body states is reached already: a move to the seat a member holds changes nothing.
    assert seats_of(client, team["id"], 4, [(starting[1], "STARTER")]).json() == first.json()
    assert snapshot(storage) == after_first


def test_the_captain_may_move_themselves(client, storage):
    team, _starting, _waiting = team_with(client, storage, starters=1)
    captain = user_id_of(storage)
    moved = seats_of(client, team["id"], 2, [(captain, "SUBSTITUTE")])
    assert moved.status_code == 200, moved.text
    assert (moved.json()["mySeat"], moved.json()["myRole"]) == ("SUBSTITUTE", "CAPTAIN")
    assert holders(storage, team["id"])[captain] == "SUBSTITUTE"


def test_a_seat_change_writes_only_the_seats_and_the_count(client, storage):
    team, starting, waiting = team_with(client, storage, starters=2, substitutes=1)
    tables = ("teams", "memberships", "seat_pools", "users", "matches")
    before = {table: rows(storage, f"SELECT * FROM {table} ORDER BY rowid") for table in tables}
    assert seats_of(client, team["id"], 5, [(starting[0], "SUBSTITUTE"), (waiting[0], "STARTER")]).status_code == 200
    after = {table: rows(storage, f"SELECT * FROM {table} ORDER BY rowid") for table in tables}
    expected_seats = {starting[0]: "SUBSTITUTE", waiting[0]: "STARTER"}
    assert after["memberships"] == [{**row, "seat": expected_seats.get(row["user_id"], row["seat"])}
                                    for row in before["memberships"]]
    assert after["seat_pools"] == [{**row, "substitute_seats": 5} for row in before["seat_pools"]]
    for table in ("teams", "users", "matches"):
        assert after[table] == before[table], table
