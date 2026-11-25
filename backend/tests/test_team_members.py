"""Team members: jerseys, leaving, removal and blocks, the new-member badge, the captaincy transfer, team deletion and
the team step of account deletion (docs/API_V1.md, section 10, Takımlar; design v5.1 sections 3.7, 4.3 and 4.6;
ADR-0120).

B4 has no join, so the members a test needs are seeded straight into SQLite (tests.helpers.seed_member).
Every team name and username below is made up by the tests.
"""
import sqlite3
import threading
import time
import uuid
from datetime import timedelta

import pytest

from app import accounts, teams
from tests.helpers import OWNER, STRANGER, as_account, create_team, rows, seed_member, user_id_of
from tests.test_access import refused_as, snapshot
from tests.test_match_delete import (  # noqa: F401 (cheap_hashing is a fixture)
    DELETED_NAME, DELETED_USER, cheap_hashing, database_bytes, delete_account, deleting_account,
)


def team_url(team_id, *parts):
    return "/".join([f"/v1/teams/{team_id}", *parts])


def set_jersey(caller, team_id, user_id, jersey):
    return caller.put(team_url(team_id, "members", user_id, "jersey"), json={"jersey": jersey})


def remove(caller, team_id, user_id):
    return caller.delete(team_url(team_id, "members", user_id))


def transfer(caller, team_id, user_id):
    return caller.post(team_url(team_id, "captain"), json={"userId": user_id})


def joins_seen(caller, team_id, up_to):
    return caller.post(team_url(team_id, "joins-seen"), json={"upTo": up_to})


def captain_of(team):
    return team["members"][0]["userId"]


def team_rows(storage, team):
    """Every row the team owns: the team, its pool, its memberships and its pool's blocks."""
    return {"teams": rows(storage, "SELECT * FROM teams WHERE id=?", team["id"]),
            "seat_pools": rows(storage, "SELECT * FROM seat_pools WHERE id=? OR team_id=?", team["poolId"], team["id"]),
            "memberships": rows(storage, "SELECT * FROM memberships WHERE team_id=?", team["id"]),
            "pool_blocks": rows(storage, "SELECT * FROM pool_blocks WHERE pool_id=?", team["poolId"])}


NOTHING = {"teams": [], "seat_pools": [], "memberships": [], "pool_blocks": []}


def captaincies(storage, user_id):
    return rows(storage, "SELECT COUNT(*) AS n FROM memberships WHERE user_id=? AND role='CAPTAIN'", user_id)[0]["n"]


def at_once(*calls):
    """Run the calls in two threads that start together, and return their answers."""
    barrier = threading.Barrier(len(calls))
    answers = []

    def run(call):
        barrier.wait(5)
        answers.append(call())

    threads = [threading.Thread(target=run, args=(call,)) for call in calls]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(30)
    return answers


def slowed(monkeypatch, function_name, seconds=0.2):
    """Slow down a function of teams.py that the routes call inside their write transaction, so a request that
    arrives together with another one finds the other holding the transaction open."""
    real = getattr(teams, function_name)

    def slow(*arguments):
        found = real(*arguments)
        time.sleep(seconds)
        return found

    monkeypatch.setattr(teams, function_name, slow)


# --- jerseys --------------------------------------------------------------------------------------------

def test_a_member_sets_their_own_jersey_and_the_captain_sets_anyones(client, storage):
    team = create_team(client, name="Kuzey Spor")
    captain_id = captain_of(team)
    member_id = seed_member(storage, team["id"], "test.uye", seat="SUBSTITUTE")
    other_id = seed_member(storage, team["id"], "test.oteki")
    member = as_account(client, storage, "test.uye")
    own = set_jersey(member, team["id"], member_id, 7)
    assert own.status_code == 200, own.text
    joined_at = rows(storage, "SELECT joined_at FROM memberships WHERE user_id=?", member_id)[0]["joined_at"]
    assert own.json() == {"userId": member_id, "displayName": "Test test.uye", "jersey": 7, "role": "MEMBER",
                          "seat": "SUBSTITUTE", "joinedAt": joined_at}
    # The answer is the member's element of the team.
    assert own.json() in client.get(team_url(team["id"])).json()["members"]
    # The captain sets another member's jersey, its own and the one the member just set.
    assert set_jersey(client, team["id"], other_id, 12).json()["jersey"] == 12
    captains = set_jersey(client, team["id"], captain_id, 1)
    assert (captains.json()["userId"], captains.json()["role"], captains.json()["jersey"]) == (captain_id, "CAPTAIN", 1)
    assert set_jersey(client, team["id"], member_id, 8).json()["jersey"] == 8
    # A member never sets another member's jersey, the captain's included.
    before = snapshot(storage)
    for target in (other_id, captain_id):
        refused = set_jersey(member, team["id"], target, 99)
        assert refused_as(refused, 403, "CAPTAIN_ONLY")["details"] == {}
    assert snapshot(storage) == before
    read = client.get(team_url(team["id"])).json()
    assert {entry["userId"]: entry["jersey"] for entry in read["members"]} == {captain_id: 1, member_id: 8, other_id: 12}
    assert (read["myJersey"], member.get(team_url(team["id"])).json()["myJersey"]) == (1, 8)


def test_a_jersey_outside_1_to_99_answers_422_and_null_clears_it(client, storage):
    team = create_team(client, name="Kuzey Spor", jersey=10)
    url = team_url(team["id"], "members", captain_of(team), "jersey")
    before = snapshot(storage)
    # The body states the jersey: a missing one is not read as "clear it".
    for body in ({"jersey": 0}, {"jersey": 100}, {"jersey": -7}, {"jersey": "7"}, {"jersey": 7.5}, {"jersey": True},
                 {}, {"jersey": 7, "seat": "STARTER"}):
        refused_as(client.put(url, json=body), 422, "VALIDATION_ERROR")
    assert snapshot(storage) == before
    for jersey in (1, 99):
        assert client.put(url, json={"jersey": jersey}).json()["jersey"] == jersey
    cleared = client.put(url, json={"jersey": None})
    assert cleared.status_code == 200 and cleared.json()["jersey"] is None
    assert rows(storage, "SELECT jersey FROM memberships WHERE team_id=?", team["id"]) == [{"jersey": None}]


def test_a_taken_jersey_answers_409_jersey_taken(client, storage, monkeypatch):
    team = create_team(client, name="Kuzey Spor", jersey=7)
    captain_id = captain_of(team)
    member_id = seed_member(storage, team["id"], "test.uye", jersey=4)
    member = as_account(client, storage, "test.uye")
    before = snapshot(storage)
    refused = set_jersey(member, team["id"], member_id, 7)
    assert refused_as(refused, 409, "JERSEY_TAKEN")["details"] == {"jersey": 7}
    # The captain sets anyone's jersey, but not to a number another member wears.
    assert refused_as(set_jersey(client, team["id"], member_id, 7), 409, "JERSEY_TAKEN")["details"] == {"jersey": 7}
    assert snapshot(storage) == before
    # The member's own number is no conflict, and neither is a number worn in another team.
    assert set_jersey(member, team["id"], member_id, 4).json()["jersey"] == 4
    create_team(as_account(client, storage, "test.baska.kaptan"), name="Başka Takım", jersey=9)
    assert set_jersey(member, team["id"], member_id, 9).json()["jersey"] == 9
    # A number that its wearer gave up is free again.
    assert set_jersey(client, team["id"], captain_id, None).json()["jersey"] is None
    assert set_jersey(member, team["id"], member_id, 7).json()["jersey"] == 7
    # A write that the check did not see meets the unique index, which answers the same.
    monkeypatch.setattr(teams, "jersey_holder", lambda db, team_id, jersey, user_id: None)
    before = snapshot(storage)
    refused = set_jersey(client, team["id"], captain_id, 7)
    assert refused_as(refused, 409, "JERSEY_TAKEN")["details"] == {"jersey": 7}
    assert snapshot(storage) == before


def test_jersey_uniqueness_holds_under_two_threads(client, storage, monkeypatch):
    team = create_team(client, name="Kuzey Spor")
    names = ("test.birinci", "test.ikinci")
    ids = {name: seed_member(storage, team["id"], name) for name in names}
    callers = {name: as_account(client, storage, name) for name in names}
    slowed(monkeypatch, "membership")
    answers = at_once(*[lambda name=name: set_jersey(callers[name], team["id"], ids[name], 7) for name in names])
    assert sorted(answer.status_code for answer in answers) == [200, 409]
    winner = next(answer for answer in answers if answer.status_code == 200).json()["userId"]
    refused = next(answer for answer in answers if answer.status_code == 409)
    assert refused_as(refused, 409, "JERSEY_TAKEN")["details"] == {"jersey": 7}
    assert rows(storage, "SELECT user_id FROM memberships WHERE team_id=? AND jersey=7", team["id"]) == [
        {"user_id": winner}]


# --- leaving, removal and blocks ---------------------------------------------------------------------------

def test_a_member_leaves_and_no_block_is_written(client, storage):
    team = create_team(client, name="Kuzey Spor")
    member_id = seed_member(storage, team["id"], "test.uye", seat="SUBSTITUTE", jersey=5)
    member = as_account(client, storage, "test.uye")
    left = remove(member, team["id"], member_id)
    assert left.status_code == 204 and left.content == b""
    assert rows(storage, "SELECT user_id FROM memberships WHERE team_id=?", team["id"]) == [{"user_id": captain_of(team)}]
    assert rows(storage, "SELECT * FROM pool_blocks") == []
    read = client.get(team_url(team["id"])).json()
    assert read["seats"]["substitute"] == {"seats": 2, "taken": 0, "left": 2, "deciding": 0}
    # The account stays; only its membership went. A second leave finds no membership to end.
    assert rows(storage, "SELECT username FROM users WHERE id=?", member_id) == [{"username": "test.uye"}]
    refused_as(remove(member, team["id"], member_id), 403, "NOT_A_MEMBER")


def test_the_captain_removes_a_member_and_blocks_the_account(client, storage, clock):
    team = create_team(client, name="Kuzey Spor")
    removed_id = seed_member(storage, team["id"], "test.cikarilan", seat="SUBSTITUTE", jersey=5)
    kept_id = seed_member(storage, team["id"], "test.kalan")
    kept = as_account(client, storage, "test.kalan")
    # A member removes nobody but themselves.
    before = snapshot(storage)
    for target in (removed_id, captain_of(team)):
        assert refused_as(remove(kept, team["id"], target), 403, "CAPTAIN_ONLY")["details"] == {}
    assert snapshot(storage) == before
    clock.advance(minutes=3)
    removed = remove(client, team["id"], removed_id)
    assert removed.status_code == 204 and removed.content == b""
    assert rows(storage, "SELECT pool_id, user_id, created_at FROM pool_blocks") == [
        {"pool_id": team["poolId"], "user_id": removed_id, "created_at": accounts.iso(clock.now)}]
    read = client.get(team_url(team["id"])).json()
    assert [entry["userId"] for entry in read["members"]] == [captain_of(team), kept_id]
    # The removed member's seat is free again, with its type, and so is the jersey.
    assert read["seats"]["substitute"] == {"seats": 2, "taken": 0, "left": 2, "deciding": 0}
    assert read["seats"]["starter"] == {"seats": 6, "taken": 2, "left": 4, "deciding": 0}
    assert set_jersey(kept, team["id"], kept_id, 5).json()["jersey"] == 5


def test_the_captain_lists_the_blocked_people_with_their_display_names(client, storage, clock):
    team = create_team(client, name="Kuzey Spor")
    first = seed_member(storage, team["id"], "test.ilk")
    second = seed_member(storage, team["id"], "test.ikinci")
    seed_member(storage, team["id"], "test.uye")
    others = as_account(client, storage, "test.baska.kaptan")
    other_team = create_team(others, name="Başka Takım")
    third = seed_member(storage, other_team["id"], "test.ucuncu")
    url = team_url(team["id"], "blocks")
    assert client.get(url).json() == {"blocked": []}
    # Removed in this order, listed in the order of the removals.
    first_moment = accounts.iso(clock.now)
    assert remove(client, team["id"], second).status_code == 204
    clock.advance(minutes=1)
    assert remove(client, team["id"], first).status_code == 204
    assert remove(others, other_team["id"], third).status_code == 204
    listed = client.get(url)
    assert listed.status_code == 200
    assert listed.json() == {"blocked": [
        {"userId": second, "displayName": "Test test.ikinci", "blockedAt": first_moment},
        {"userId": first, "displayName": "Test test.ilk", "blockedAt": accounts.iso(clock.now)}]}
    assert others.get(team_url(other_team["id"], "blocks")).json()["blocked"] == [
        {"userId": third, "displayName": "Test test.ucuncu", "blockedAt": accounts.iso(clock.now)}]
    # Only the captain sees who was removed.
    refused = as_account(client, storage, "test.uye").get(url)
    assert refused_as(refused, 403, "CAPTAIN_ONLY")["details"] == {}
    assert "test.ilk" not in refused.text and "test.ikinci" not in refused.text
    refused_as(as_account(client, storage, "test.ilk").get(url), 403, "NOT_A_MEMBER")
    # A second removal of the same account keeps one block, dated by the later removal and listed by that date.
    seed_member(storage, team["id"], "test.ikinci")
    clock.advance(minutes=1)
    assert remove(client, team["id"], second).status_code == 204
    assert [(entry["userId"], entry["blockedAt"]) for entry in client.get(url).json()["blocked"]] == [
        (first, accounts.iso(clock.now - timedelta(minutes=1))), (second, accounts.iso(clock.now))]


def test_unblock_removes_the_block_and_a_repeat_answers_204(client, storage):
    team = create_team(client, name="Kuzey Spor")
    removed_id = seed_member(storage, team["id"], "test.cikarilan")
    seed_member(storage, team["id"], "test.uye")
    assert remove(client, team["id"], removed_id).status_code == 204
    # Another captain blocked the same account in another team.
    others = as_account(client, storage, "test.baska.kaptan")
    other_team = create_team(others, name="Başka Takım")
    seed_member(storage, other_team["id"], "test.cikarilan")
    assert remove(others, other_team["id"], removed_id).status_code == 204
    url = team_url(team["id"], "blocks", removed_id)
    before = snapshot(storage)
    assert refused_as(as_account(client, storage, "test.uye").delete(url), 403, "CAPTAIN_ONLY")["details"] == {}
    refused_as(as_account(client, storage, "test.cikarilan").delete(url), 403, "NOT_A_MEMBER")
    assert snapshot(storage) == before
    lifted = client.delete(url)
    assert lifted.status_code == 204 and lifted.content == b""
    assert client.get(team_url(team["id"], "blocks")).json() == {"blocked": []}
    assert rows(storage, "SELECT pool_id, user_id FROM pool_blocks") == [
        {"pool_id": other_team["poolId"], "user_id": removed_id}]
    # A repeat, and a block that never existed, answer 204 and change nothing.
    before = snapshot(storage)
    assert client.delete(url).status_code == 204
    assert client.delete(team_url(team["id"], "blocks", str(uuid.uuid4()))).status_code == 204
    assert snapshot(storage) == before


def test_a_captain_with_other_members_can_not_leave(client, storage):
    team = create_team(client, name="Kuzey Spor")
    seed_member(storage, team["id"], "test.uye")
    before = snapshot(storage)
    refused = remove(client, team["id"], captain_of(team))
    assert refused_as(refused, 409, "CAPTAIN_MUST_TRANSFER")["details"] == {}
    assert snapshot(storage) == before


def test_a_captain_alone_who_leaves_deletes_the_team(client, storage):
    team = create_team(client, name="Tek Kişilik")
    removed_id = seed_member(storage, team["id"], "test.cikarilan")
    assert remove(client, team["id"], removed_id).status_code == 204
    kept = create_team(client, name="Kalan Takım")
    assert team_rows(storage, team)["pool_blocks"] != []
    left = remove(client, team["id"], captain_of(team))
    assert left.status_code == 204 and left.content == b""
    assert team_rows(storage, team) == NOTHING
    refused_as(client.get(team_url(team["id"])), 404, "TEAM_NOT_FOUND")
    assert [listed["id"] for listed in client.get("/v1/teams").json()["teams"]] == [kept["id"]]
    # Both accounts stay.
    assert {row["username"] for row in rows(storage, "SELECT username FROM users")} == {OWNER, "test.cikarilan"}


def test_removing_someone_who_is_not_a_member_answers_404_member_not_found(client, storage, stranger):
    team = create_team(client, name="Kuzey Spor")
    seed_member(storage, team["id"], "test.uye")
    member = as_account(client, storage, "test.uye")
    # The stranger is a member of another team, not of this one.
    create_team(stranger, name="Yabancının Takımı")
    before = snapshot(storage)
    for caller in (client, member):
        for target in (user_id_of(storage, STRANGER), str(uuid.uuid4()), "kimlik-degil"):
            refused_as(remove(caller, team["id"], target), 404, "MEMBER_NOT_FOUND")
            refused_as(set_jersey(caller, team["id"], target, 5), 404, "MEMBER_NOT_FOUND")
    assert snapshot(storage) == before


def test_a_removed_member_no_longer_reads_the_team(client, storage):
    team = create_team(client, name="Kuzey Spor")
    member_id = seed_member(storage, team["id"], "test.uye")
    member = as_account(client, storage, "test.uye")
    assert member.get(team_url(team["id"])).status_code == 200
    assert remove(client, team["id"], member_id).status_code == 204
    refused = member.get(team_url(team["id"]))
    assert refused_as(refused, 403, "NOT_A_MEMBER")["details"] == {}
    assert "Kuzey Spor" not in refused.text and team["tag"] not in refused.text
    assert member.get("/v1/teams").json() == {"teams": []}
    assert member.get("/v1/me").json()["teams"] == []
    refused_as(set_jersey(member, team["id"], member_id, 3), 403, "NOT_A_MEMBER")
    refused_as(remove(member, team["id"], member_id), 403, "NOT_A_MEMBER")


def test_a_left_or_removed_member_leaves_no_row_of_the_membership(client, storage):
    team = create_team(client, name="Kuzey Spor")
    leaving = seed_member(storage, team["id"], "test.ayrilan", jersey=3)
    removed = seed_member(storage, team["id"], "test.cikarilan", jersey=4)
    assert remove(as_account(client, storage, "test.ayrilan"), team["id"], leaving).status_code == 204
    assert remove(client, team["id"], removed).status_code == 204
    assert rows(storage, "SELECT user_id FROM memberships") == [{"user_id": captain_of(team)}]
    # Only the removal wrote a block.
    assert rows(storage, "SELECT pool_id, user_id FROM pool_blocks") == [{"pool_id": team["poolId"], "user_id": removed}]


# --- the new-member badge ------------------------------------------------------------------------------

def test_new_joins_counts_members_after_the_captains_mark_and_joins_seen_moves_it(client, storage, clock):
    team = create_team(client, name="Kuzey Spor")
    clock.advance(minutes=1)
    first_joined = accounts.iso(clock.now)
    seed_member(storage, team["id"], "test.birinci")
    clock.advance(minutes=1)
    second_joined = accounts.iso(clock.now)
    seed_member(storage, team["id"], "test.ikinci")
    clock.advance(minutes=1)

    def new_joins():
        listed = client.get("/v1/teams").json()["teams"][0]["newJoins"]
        assert client.get(team_url(team["id"])).json()["newJoins"] == listed
        return listed

    assert new_joins() == 2
    # Members get no count and can not move the captain's mark.
    member = as_account(client, storage, "test.birinci")
    assert member.get(team_url(team["id"])).json()["newJoins"] is None
    before = snapshot(storage)
    refused = joins_seen(member, team["id"], accounts.iso(clock.now))
    assert refused_as(refused, 403, "CAPTAIN_ONLY")["details"] == {}
    assert snapshot(storage) == before
    seen = joins_seen(client, team["id"], first_joined)
    assert seen.status_code == 204 and seen.content == b""
    assert new_joins() == 1
    assert joins_seen(client, team["id"], second_joined).status_code == 204
    assert new_joins() == 0
    clock.advance(minutes=1)
    seed_member(storage, team["id"], "test.ucuncu")
    assert new_joins() == 1


def test_joins_seen_never_moves_the_mark_back_or_past_now(client, storage, clock):
    team = create_team(client, name="Kuzey Spor")
    created = accounts.iso(clock.now)
    url = team_url(team["id"], "joins-seen")

    def mark():
        return rows(storage, "SELECT joins_seen_at FROM memberships WHERE team_id=? AND user_id=?", team["id"],
                    captain_of(team))[0]["joins_seen_at"]

    clock.advance(hours=1)
    # An earlier moment leaves the mark where it is.
    assert joins_seen(client, team["id"], accounts.iso(clock.now - timedelta(hours=2))).status_code == 204
    assert mark() == created
    # A moment after the server's time stops at the server's time, so a later join still counts as new.
    assert joins_seen(client, team["id"], "2099-01-01T00:00:00Z").status_code == 204
    assert mark() == accounts.iso(clock.now)
    clock.advance(minutes=5)
    seed_member(storage, team["id"], "test.yeni")
    assert client.get(team_url(team["id"])).json()["newJoins"] == 1
    # The server's clock went back: the mark still never moves back.
    seen_at = mark()
    clock.advance(hours=-3)
    assert joins_seen(client, team["id"], accounts.iso(clock.now)).status_code == 204
    assert mark() == seen_at
    # A malformed moment is refused before anything is read.
    before = snapshot(storage)
    for body in ({"upTo": "2026-11-17"}, {"upTo": "2026-11-17T12:00:00"}, {"upTo": "2026-11-17T12:00:00+00:00"},
                 {"upTo": "2026-11-17T12:00:00.000Z"}, {"upTo": "2026-13-01T00:00:00Z"}, {"upTo": "2026-02-30T12:00:00Z"},
                 {"upTo": "٢٠٢٦-11-17T12:00:00Z"}, {"upTo": 5}, {}, {"upTo": created, "extra": 1}):
        refused_as(client.post(url, json=body), 422, "VALIDATION_ERROR")
    assert snapshot(storage) == before


# --- the captaincy transfer ------------------------------------------------------------------------------

def test_a_transfer_makes_the_member_captain_and_keeps_the_seats(client, storage, clock):
    team = create_team(client, name="Kuzey Spor", jersey=10)
    captain_id = captain_of(team)
    clock.advance(minutes=1)
    heir_id = seed_member(storage, team["id"], "test.varis", seat="SUBSTITUTE", jersey=4)
    clock.advance(minutes=1)
    other_id = seed_member(storage, team["id"], "test.oteki")
    clock.advance(minutes=1)
    seats_before = client.get(team_url(team["id"])).json()["seats"]
    moved = transfer(client, team["id"], heir_id)
    assert moved.status_code == 200, moved.text
    # The answer is the team as the old captain now sees it: as a member.
    answer = moved.json()
    assert (answer["myRole"], answer["mySeat"], answer["myJersey"], answer["newJoins"], answer["seats"]["waiting"]) == (
        "MEMBER", "STARTER", 10, None, None)
    assert [(entry["userId"], entry["role"], entry["seat"], entry["jersey"]) for entry in answer["members"]] == [
        (captain_id, "MEMBER", "STARTER", 10), (heir_id, "CAPTAIN", "SUBSTITUTE", 4), (other_id, "MEMBER", "STARTER", None)]
    assert answer == client.get(team_url(team["id"])).json()
    heir = as_account(client, storage, "test.varis")
    heirs = heir.get(team_url(team["id"])).json()
    # Nobody's seat moved with the captaincy, and the new captain's mark is the moment of the transfer.
    assert (heirs["myRole"], heirs["mySeat"], heirs["newJoins"], heirs["seats"]) == ("CAPTAIN", "SUBSTITUTE", 0,
                                                                                    seats_before)
    assert rows(storage, "SELECT joins_seen_at FROM memberships WHERE user_id=?", heir_id) == [
        {"joins_seen_at": accounts.iso(clock.now)}]
    # The old captain is a member now: the captain routes refuse it and serve the new captain.
    refused_as(transfer(client, team["id"], captain_id), 403, "CAPTAIN_ONLY")
    assert heir.get(team_url(team["id"], "blocks")).status_code == 200
    assert transfer(heir, team["id"], captain_id).status_code == 200
    assert rows(storage, "SELECT user_id FROM memberships WHERE team_id=? AND role='CAPTAIN'", team["id"]) == [
        {"user_id": captain_id}]


def test_a_transfer_to_a_member_who_captains_3_teams_answers_team_limit_reached_captain(client, storage):
    team = create_team(client, name="Kuzey Spor")
    heir_id = seed_member(storage, team["id"], "test.varis")
    heir = as_account(client, storage, "test.varis")
    heirs_teams = [create_team(heir, name=f"Varisin Takımı {index + 1}") for index in range(3)]
    before = snapshot(storage)
    refused = transfer(client, team["id"], heir_id)
    assert refused_as(refused, 409, "TEAM_LIMIT_REACHED")["details"] == {
        "scope": "CAPTAIN", "limit": teams.CAPTAIN_LIMIT, "userId": heir_id}
    assert snapshot(storage) == before
    # With one captaincy fewer the transfer passes, and the heir captains 3 teams.
    assert heir.delete(team_url(heirs_teams[0]["id"])).status_code == 204
    assert transfer(client, team["id"], heir_id).status_code == 200
    assert captaincies(storage, heir_id) == 3


def test_two_transfers_at_once_leave_exactly_one_captain(client, storage, monkeypatch):
    team = create_team(client, name="Kuzey Spor")
    heirs = [seed_member(storage, team["id"], name) for name in ("test.b", "test.c")]
    slowed(monkeypatch, "membership")
    answers = at_once(*[lambda heir=heir: transfer(client, team["id"], heir) for heir in heirs])
    assert sorted(answer.status_code for answer in answers) == [200, 403]
    refused = next(answer for answer in answers if answer.status_code == 403)
    assert refused_as(refused, 403, "CAPTAIN_ONLY")["details"] == {}
    winner = next(answer for answer in answers if answer.status_code == 200).json()
    new_captain = next(entry["userId"] for entry in winner["members"] if entry["role"] == "CAPTAIN")
    assert new_captain in heirs
    assert rows(storage, "SELECT user_id FROM memberships WHERE team_id=? AND role='CAPTAIN'", team["id"]) == [
        {"user_id": new_captain}]


def test_a_transfer_to_someone_who_is_not_a_member_answers_not_a_team_member(client, storage, stranger):
    team = create_team(client, name="Kuzey Spor")
    member_id = seed_member(storage, team["id"], "test.uye")
    create_team(stranger, name="Yabancının Takımı")
    url = team_url(team["id"], "captain")
    before = snapshot(storage)
    for target in (user_id_of(storage, STRANGER), str(uuid.uuid4())):
        refused = transfer(client, team["id"], target)
        assert refused_as(refused, 422, "NOT_A_TEAM_MEMBER")["details"] == {"userId": target}
    # A member is refused as a member before the target is looked at.
    refused_as(transfer(as_account(client, storage, "test.uye"), team["id"], user_id_of(storage, STRANGER)), 403,
               "CAPTAIN_ONLY")
    for body in ({}, {"userId": member_id, "seat": "STARTER"}, {"userId": 5}, {"userId": "x" * 65}):
        refused_as(client.post(url, json=body), 422, "VALIDATION_ERROR")
    assert snapshot(storage) == before


def test_a_transfer_to_oneself_changes_nothing(client, storage):
    team = create_team(client, name="Kuzey Spor")
    seed_member(storage, team["id"], "test.uye")
    # The caller captains 3 teams: a transfer to oneself is no fourth captaincy.
    create_team(client, name="İkinci")
    create_team(client, name="Üçüncü")
    read = client.get(team_url(team["id"])).json()
    before = snapshot(storage)
    same = transfer(client, team["id"], captain_of(team))
    assert same.status_code == 200 and same.json() == read
    assert snapshot(storage) == before


# --- account deletion ----------------------------------------------------------------------------------

def test_account_deletion_moves_a_captaincy_to_the_earliest_joined_member_even_past_the_limit(storage, cheap_hashing,
                                                                                              clock):
    with deleting_account(storage) as (client, user_id):
        team = create_team(client, name="Devredilen")
        # The member seeded first joined later: joined_at decides, not the order of the rows.
        later = seed_member(storage, team["id"], "test.sonraki", joined_at=accounts.iso(clock.now + timedelta(minutes=2)))
        heir_id = seed_member(storage, team["id"], "test.varis", seat="SUBSTITUTE", jersey=8,
                              joined_at=accounts.iso(clock.now + timedelta(minutes=1)))
        heir = as_account(client, storage, "test.varis")
        for index in range(3):
            create_team(heir, name=f"Varisin Takımı {index + 1}")
        assert captaincies(storage, heir_id) == teams.CAPTAIN_LIMIT
        # In a second team two members joined at the same moment, and the one who joined first in the table wins.
        tied = create_team(client, name="Eşit Takım")
        clock.advance(minutes=5)
        first = seed_member(storage, tied["id"], "test.esit.bir")
        second = seed_member(storage, tied["id"], "test.esit.iki")
        clock.advance(minutes=5)
        assert delete_account(client).status_code == 204
        assert rows(storage, "SELECT user_id, role, seat, jersey, joins_seen_at FROM memberships WHERE team_id=?"
                             " ORDER BY joined_at", team["id"]) == [
            {"user_id": heir_id, "role": "CAPTAIN", "seat": "SUBSTITUTE", "jersey": 8,
             "joins_seen_at": accounts.iso(clock.now)},
            {"user_id": later, "role": "MEMBER", "seat": "STARTER", "jersey": None, "joins_seen_at": None}]
        assert captaincies(storage, heir_id) == 4
        assert rows(storage, "SELECT user_id, role FROM memberships WHERE team_id=? ORDER BY rowid", tied["id"]) == [
            {"user_id": first, "role": "CAPTAIN"}, {"user_id": second, "role": "MEMBER"}]
        heirs = heir.get(team_url(team["id"])).json()
        assert (heirs["myRole"], heirs["newJoins"], [entry["userId"] for entry in heirs["members"]]) == (
            "CAPTAIN", 0, [heir_id, later])


def test_account_deletion_deletes_a_team_without_another_member(storage, cheap_hashing):
    with deleting_account(storage) as (client, user_id):
        alone = create_team(client, name="Yalnız Takım")
        removed_id = seed_member(storage, alone["id"], "test.cikarilan")
        assert remove(client, alone["id"], removed_id).status_code == 204
        others = as_account(client, storage, "test.baska.kaptan")
        kept = create_team(others, name="Kalan Takım")
        assert delete_account(client).status_code == 204
        assert team_rows(storage, alone) == NOTHING
        assert others.get(team_url(kept["id"])).json()["name"] == "Kalan Takım"
        assert rows(storage, "SELECT username FROM users WHERE id=?", removed_id) == [{"username": "test.cikarilan"}]


def test_account_deletion_removes_the_accounts_memberships_and_blocks(storage, cheap_hashing):
    with deleting_account(storage) as (client, user_id):
        captain = as_account(client, storage, "test.kaptan")
        joined = create_team(captain, name="Üye Olunan")
        seed_member(storage, joined["id"], DELETED_USER, jersey=7)
        blocking = create_team(captain, name="Engelleyen")
        seed_member(storage, blocking["id"], DELETED_USER)
        assert remove(captain, blocking["id"], user_id).status_code == 204
        assert len(rows(storage, "SELECT 1 FROM pool_blocks WHERE user_id=?", user_id)) == 1
        assert delete_account(client).status_code == 204
        assert rows(storage, "SELECT * FROM memberships WHERE user_id=?", user_id) == []
        assert rows(storage, "SELECT * FROM pool_blocks WHERE user_id=?", user_id) == []
        # The other captain's teams stay, without the deleted account.
        read = captain.get(team_url(joined["id"])).json()
        assert [entry["userId"] for entry in read["members"]] == [captain_of(joined)]
        assert captain.get(team_url(blocking["id"], "blocks")).json() == {"blocked": []}
        assert set_jersey(captain, joined["id"], captain_of(joined), 7).json()["jersey"] == 7


# --- team deletion and what stays in the files ------------------------------------------------------------

def test_deleting_a_team_removes_its_pool_memberships_and_blocks_and_only_the_captain_may(client, storage, stranger):
    team = create_team(client, name="Silinecek Takım")
    seed_member(storage, team["id"], "test.uye", jersey=7)
    removed_id = seed_member(storage, team["id"], "test.cikarilan")
    assert remove(client, team["id"], removed_id).status_code == 204
    kept = create_team(client, name="Kalan Takım")
    seed_member(storage, kept["id"], "test.uye", jersey=7)
    member = as_account(client, storage, "test.uye")
    before = snapshot(storage)
    assert refused_as(member.delete(team_url(team["id"])), 403, "CAPTAIN_ONLY")["details"] == {}
    assert refused_as(stranger.delete(team_url(team["id"])), 403, "NOT_A_MEMBER")["details"] == {}
    refused_as(client.delete(team_url(str(uuid.uuid4()))), 404, "TEAM_NOT_FOUND")
    assert snapshot(storage) == before
    deleted = client.delete(team_url(team["id"]))
    assert deleted.status_code == 204 and deleted.content == b""
    assert team_rows(storage, team) == NOTHING
    for caller in (client, member):
        refused_as(caller.get(team_url(team["id"])), 404, "TEAM_NOT_FOUND")
    refused_as(client.delete(team_url(team["id"])), 404, "TEAM_NOT_FOUND")
    # The other team and every account stay.
    assert [entry["jersey"] for entry in member.get(team_url(kept["id"])).json()["members"]] == [None, 7]
    assert len(rows(storage, "SELECT 1 FROM users")) == 4


@pytest.mark.parametrize("how, holds_log", [("delete", False), ("delete", True), ("leave", True)])
def test_a_deleted_team_can_not_be_read_from_the_database_files(client, storage, monkeypatch, how, holds_log):
    draws = iter(["ZQ7X", "KW4P"])
    monkeypatch.setattr(teams, "_random_tag", lambda: next(draws))
    # A second connection that stays open keeps SQLite from clearing its write-ahead log by itself, as in
    # test_match_delete. The log then still holds the rows as first written.
    bystander = sqlite3.connect(storage / "volleystat.sqlite3") if holds_log else None
    try:
        if bystander is not None:
            assert bystander.execute("SELECT COUNT(*) FROM teams").fetchone() == (0,)
        team = create_team(client, name="GizliTakimZQ9", schedule={"weekday": 3, "time": "21:45"}, jersey=61)
        create_team(client, name="KalanTakimKW5")
        removed_id = seed_member(storage, team["id"], "test.cikarilan")
        assert remove(client, team["id"], removed_id).status_code == 204
        if how == "delete":
            seed_member(storage, team["id"], "test.uye", jersey=42)
        marks = ("GizliTakimZQ9", "ZQ7X", team["id"], team["poolId"])
        before = database_bytes(storage)
        assert all(mark.encode() in before for mark in marks), "the test can not see what it looks for"

        if how == "delete":
            assert client.delete(team_url(team["id"])).status_code == 204
        else:
            assert remove(client, team["id"], captain_of(team)).status_code == 204

        after = database_bytes(storage)
        for mark in marks:
            assert mark.encode() not in after, mark
        # The other team is still there, so the check did not pass on an emptied file.
        assert b"KalanTakimKW5" in after and b"KW4P" in after
    finally:
        if bystander is not None:
            bystander.close()


def test_an_account_deletion_leaves_no_byte_of_its_only_member_team(storage, cheap_hashing, monkeypatch):
    monkeypatch.setattr(teams, "_random_tag", lambda: "ZQ7X")
    with deleting_account(storage) as (client, user_id):
        bystander = sqlite3.connect(storage / "volleystat.sqlite3")
        try:
            assert bystander.execute("SELECT COUNT(*) FROM users").fetchone() == (1,)
            team = create_team(client, name="HesapTakimiZQ9", schedule={"weekday": 1, "time": "07:15"})
            marks = ("HesapTakimiZQ9", "ZQ7X", team["id"], team["poolId"], user_id, DELETED_USER, DELETED_NAME)
            before = database_bytes(storage)
            assert all(mark.encode() in before for mark in marks), "the test can not see what it looks for"
            assert delete_account(client).status_code == 204
            after = database_bytes(storage)
            for mark in marks:
                assert mark.encode() not in after, mark
            assert rows(storage, "SELECT * FROM teams") == []
        finally:
            bystander.close()
