"""Teams: creation, reading, settings, the tag and the creation limits (docs/API_V1.md, section 10, Takımlar;
design v5.1 sections 3.6, 4.1, 4.4 and 9.3; ADR-0120).

B4 has no join, so the members a test needs are seeded straight into SQLite (tests.helpers.seed_member).
Every team name and username below is made up by the tests.
"""
import sqlite3
import threading
import time
import uuid

import pytest

from app import accounts, teams
from tests.helpers import (
    NO_SESSION, OWNER, STRANGER, As, as_account, bearer, create_team, delete_team_rows, rows, seed_member, serve,
    user_id_of,
)
from tests.test_access import refused_as, snapshot

DAY = 24 * 3600
TEAM_KEYS = {"id", "poolId", "name", "tag", "schedule", "logo", "myRole", "mySeat", "myJersey", "newJoins", "members",
             "seats", "invite"}
MEMBER_KEYS = {"userId", "displayName", "jersey", "role", "seat", "joinedAt"}


def put_team(caller, body, team_id=None):
    return caller.put(f"/v1/teams/{team_id or uuid.uuid4()}", json=body)


def captaincies(storage, username=OWNER):
    return rows(storage, "SELECT COUNT(*) AS n FROM memberships WHERE user_id=? AND role='CAPTAIN'",
                user_id_of(storage, username))[0]["n"]


# --- creating a team --------------------------------------------------------------------------------

def test_any_account_creates_a_team_without_a_code(storage, monkeypatch):
    # v5 (R6): no captain code and no team setting; an account of every way of opening creates a team.
    monkeypatch.delenv("VOLLEYSTAT_SIGNUP", raising=False)
    monkeypatch.setattr(accounts, "SCRYPT_N", 2 ** 10)
    with serve(storage, None) as client:
        registered = client.post("/v1/auth/register", headers=NO_SESSION, json={
            "username": "acik.hesap", "displayName": "Açık Hesap", "password": "uzun-ve-tuhaf-bir-sifre"})
        assert registered.status_code == 201, registered.text
        callers = {"OPEN": As(client, bearer(registered.json()["session"]["token"])),
                   "INVITE": as_account(client, storage, "test.davetli", "INVITE"),
                   "SIGNUP_CODE": client}
        for signup_via, caller in callers.items():
            me = caller.get("/v1/me").json()["user"]
            assert rows(storage, "SELECT signup_via FROM users WHERE id=?", me["id"]) == [{"signup_via": signup_via}]
            team = create_team(caller, name=f"Takım {signup_via.title()}")
            assert set(team) == TEAM_KEYS
            assert (team["myRole"], team["mySeat"], team["myJersey"]) == ("CAPTAIN", "STARTER", None)
            assert team["seats"] == {"starter": {"seats": 6, "taken": 1, "left": 5, "deciding": 0},
                                     "substitute": {"seats": 2, "taken": 0, "left": 2, "deciding": 0},
                                     "waiting": 0}
            assert (team["invite"], team["logo"], team["schedule"], team["newJoins"]) == (None, None, None, 0)
            assert len(team["tag"]) == 4 and set(team["tag"]) <= set(accounts.CODE_ALPHABET)
            assert [set(member) for member in team["members"]] == [MEMBER_KEYS]
            assert team["members"][0] == {"userId": me["id"], "displayName": me["displayName"], "jersey": None,
                                          "role": "CAPTAIN", "seat": "STARTER", "joinedAt": team["members"][0]["joinedAt"]}
            pool = rows(storage, "SELECT kind, team_id, match_id, starter_seats, substitute_seats FROM seat_pools"
                                 " WHERE id=?", team["poolId"])
            assert pool == [{"kind": "TEAM", "team_id": team["id"], "match_id": None, "starter_seats": 6,
                             "substitute_seats": 2}]
            # The captain's mark for the new-member badge is the moment of creation.
            membership = rows(storage, "SELECT joined_at, joins_seen_at FROM memberships WHERE team_id=?", team["id"])
            assert membership == [{"joined_at": team["members"][0]["joinedAt"],
                                   "joins_seen_at": team["members"][0]["joinedAt"]}]


def test_a_repeat_by_the_creator_answers_200_and_changes_nothing(client, storage):
    team_id = str(uuid.uuid4())
    body = {"name": "Kuzey Spor", "substituteSeats": 3, "schedule": {"weekday": 2, "time": "20:00"}, "jersey": 7}
    created = put_team(client, body, team_id)
    assert created.status_code == 201, created.text
    assert (created.json()["schedule"], created.json()["myJersey"]) == ({"weekday": 2, "time": "20:00"}, 7)
    assert created.json()["seats"]["substitute"]["seats"] == 3
    before = snapshot(storage)
    # The client lost the answer and repeats; or it repeats with another body. Neither changes the team.
    for again in (body, {"name": "Başka Ad", "substituteSeats": 0, "jersey": 99}):
        repeated = put_team(client, again, team_id)
        assert repeated.status_code == 200 and repeated.json() == created.json()
    assert snapshot(storage) == before


def test_a_repeat_by_another_account_answers_409_team_id_taken_and_shows_nothing(client, storage, stranger):
    team = create_team(client, name="Gizli Takım")
    before = snapshot(storage)
    refused = put_team(stranger, {"name": "Başka Takım"}, team["id"])
    assert refused_as(refused, 409, "TEAM_ID_TAKEN")["details"] == {}
    for secret in ("Gizli Takım", team["tag"], team["poolId"], user_id_of(storage)):
        assert secret not in refused.text
    assert snapshot(storage) == before


@pytest.mark.parametrize("team_id", [
    "not-a-uuid", str(uuid.uuid4()).upper(), uuid.uuid4().hex, "{" + str(uuid.uuid4()) + "}", " " + str(uuid.uuid4())])
def test_a_team_id_that_is_not_a_lowercase_uuid_answers_422_team_id_invalid(client, storage, team_id):
    before = snapshot(storage)
    refused_as(put_team(client, {"name": "Kuzey Spor"}, team_id), 422, "TEAM_ID_INVALID")
    assert snapshot(storage) == before


@pytest.mark.parametrize("name", [
    "K",                       # one character
    "K" * 41,                  # 41 characters
    "      ",                  # only spaces
    "...",                     # no letter or digit
    "Kuzey\u0007Spor",         # a control character (Cc)
    "Kuzey\tSpor",             # a tab is a control character too: it is refused, not turned into a space
    "Kuzey\u200dSpor",         # a zero-width joiner (Cf)
    "\u202eKuzey Spor",        # a bidi mark (Cf)
])
def test_a_team_name_outside_the_rule_answers_422_name_invalid(client, storage, name):
    before = snapshot(storage)
    refused_as(put_team(client, {"name": name}), 422, "NAME_INVALID")
    assert snapshot(storage) == before


def test_a_team_name_is_stored_after_nfc_trim_and_collapsed_spaces(client, storage):
    cases = {
        "  Kuzey    Spor  ": "Kuzey Spor",
        "Genc\u0327lik Kulu\u0308bu\u0308": "Gençlik Kulübü",   # decomposed letters become one character each
        "Moda\u00a0\u00a0 Gençlik": "Moda Gençlik",            # no-break spaces collapse like spaces
        " " + "K" * 40 + " ": "K" * 40,                         # counted after the trim
    }
    for sent, stored in cases.items():
        team = create_team(as_account(client, storage, f"test.ad{len(stored)}{stored[0].lower()}"), name=sent)
        assert team["name"] == stored
        assert rows(storage, "SELECT name FROM teams WHERE id=?", team["id"]) == [{"name": stored}]


@pytest.mark.parametrize("name", ["Kuzey Spor & Co.", "Ç.Ş. Gençlik", "A1", "Ü" * 40, "Spor #1 (Kadın) / 2026!"])
def test_a_team_name_with_punctuation_and_symbols_is_accepted(client, storage, name):
    # The rule refuses only control and format characters: any other character may stand in a name of 2 to 40
    # characters that holds at least one letter or digit.
    team = create_team(client, name=name)
    assert team["name"] == name
    assert rows(storage, "SELECT name FROM teams WHERE id=?", team["id"]) == [{"name": name}]


@pytest.mark.parametrize("schedule", [
    {"weekday": 0, "time": "20:00"}, {"weekday": 8, "time": "20:00"}, {"weekday": 2, "time": "24:00"},
    {"weekday": 2, "time": "7:30"}, {"weekday": 2, "time": "20:60"}, {"weekday": 2}, {"time": "20:00"},
    {"weekday": "2", "time": "20:00"}, {"weekday": 2, "time": "20:00", "timezone": "UTC"}])
def test_a_schedule_outside_the_rule_answers_422(client, storage, schedule):
    team = create_team(client, name="Kuzey Spor")
    before = snapshot(storage)
    refused_as(put_team(client, {"name": "Güney Spor", "schedule": schedule}), 422, "VALIDATION_ERROR")
    refused_as(client.put(f"/v1/teams/{team['id']}/settings", json={"name": "Kuzey Spor", "schedule": schedule}),
               422, "VALIDATION_ERROR")
    assert snapshot(storage) == before


@pytest.mark.parametrize("jersey", [0, 100, -1, "7", 7.5, True])
def test_a_creators_jersey_outside_1_to_99_answers_422(client, storage, jersey):
    before = snapshot(storage)
    refused_as(put_team(client, {"name": "Kuzey Spor", "jersey": jersey}), 422, "VALIDATION_ERROR")
    assert snapshot(storage) == before


# --- the limits ---------------------------------------------------------------------------------------

def test_a_fourth_captaincy_answers_team_limit_reached_captain(client, storage):
    for index in range(3):
        create_team(client, name=f"Takım {index + 1}")
    before = snapshot(storage)
    # The captain limit is read from the database and comes before the daily count, which is full as well.
    error = refused_as(put_team(client, {"name": "Dördüncü"}), 409, "TEAM_LIMIT_REACHED")
    assert error["details"] == {"scope": "CAPTAIN", "limit": teams.CAPTAIN_LIMIT}
    assert teams.CAPTAIN_LIMIT == 3
    assert snapshot(storage) == before


def test_a_twenty_first_membership_answers_team_limit_reached_member(client, storage):
    for index in range(19):
        other = as_account(client, storage, f"test.kaptan{index}")
        seed_member(storage, create_team(other, name=f"Takım {index + 1}")["id"], OWNER)
    # Nineteen memberships without a captaincy leave room for one more, the caller's own team.
    create_team(client, name="Yirminci")
    before = snapshot(storage)
    error = refused_as(put_team(client, {"name": "Yirmi Birinci"}), 409, "TEAM_LIMIT_REACHED")
    assert error["details"] == {"scope": "MEMBER", "limit": teams.MEMBER_LIMIT}
    assert teams.MEMBER_LIMIT == 20
    assert snapshot(storage) == before


def test_a_fourth_creation_in_a_day_answers_429_teams(client, storage, clock):
    created = [create_team(client, name=f"Takım {index + 1}") for index in range(3)]
    # The route that deletes a team comes with the members part. Until then the rows go straight from SQLite.
    delete_team_rows(storage, created[0]["id"])
    # The caller captains 2 teams, so the captain limit (3) can not answer first.
    assert captaincies(storage) == 2
    before = snapshot(storage)
    refused = put_team(client, {"name": "Dördüncü"})
    error = refused_as(refused, 429, "TOO_MANY_ATTEMPTS")
    assert error["details"] == {"scope": "TEAMS", "retryAfterSeconds": DAY}
    assert refused.headers["retry-after"] == str(DAY)
    clock.advance(seconds=DAY - 1)
    refused = put_team(client, {"name": "Dördüncü"})
    assert refused_as(refused, 429, "TOO_MANY_ATTEMPTS")["details"] == {"scope": "TEAMS", "retryAfterSeconds": 1}
    assert refused.headers["retry-after"] == "1"
    assert snapshot(storage) == before
    # A day after the first creation of the day its place is free again.
    clock.advance(seconds=1)
    create_team(client, name="Ertesi Gün")
    assert teams.CREATIONS_PER_DAY == 3


def test_a_refused_creation_takes_no_place_of_the_daily_count(client, storage, stranger, clock):
    yesterday = [create_team(client, name=f"Dünkü {index + 1}") for index in range(3)]
    taken = create_team(stranger, name="Başkasının Takımı")
    clock.advance(days=1)
    for _ in range(3):
        refused_as(put_team(client, {"name": "Fazla"}), 409, "TEAM_LIMIT_REACHED")
    refused_as(put_team(client, {"name": "Kapılmış"}, taken["id"]), 409, "TEAM_ID_TAKEN")
    refused_as(put_team(client, {"name": "x"}), 422, "NAME_INVALID")
    refused_as(put_team(client, {"name": "Yedi Yedek", "substituteSeats": 7}), 422, "SEATS_INVALID")
    refused_as(put_team(client, {"name": "Yedeksiz", "substituteSeats": 0, "captainSeat": "SUBSTITUTE"}), 422,
               "SEATS_INVALID")
    refused_as(put_team(client, {"name": "As Sayılı", "starterSeats": 6}), 422, "VALIDATION_ERROR")
    refused_as(put_team(client, {"name": "Kötü Kimlik"}, "kotu-kimlik"), 422, "TEAM_ID_INVALID")
    # A repeat of the caller's own team answers 200 and takes no place either.
    assert put_team(client, {"name": "Dünkü 1"}, yesterday[0]["id"]).status_code == 200
    # Three teams of the new day still pass, one after each deletion.
    for index, team in enumerate(yesterday):
        delete_team_rows(storage, team["id"])
        create_team(client, name=f"Bugünkü {index + 1}")


def test_a_creation_that_fails_after_its_reservation_gives_its_place_back(client, storage, monkeypatch):
    real_draw = teams._random_tag

    def broken_draw():
        raise RuntimeError("the draw of the tag broke")

    monkeypatch.setattr(teams, "_random_tag", broken_draw)
    before = snapshot(storage)
    for _ in range(2):
        with pytest.raises(RuntimeError):
            put_team(client, {"name": "Yarım Kalan"})
    assert snapshot(storage) == before
    # Neither failed creation kept its place of the day: three creations still pass.
    monkeypatch.setattr(teams, "_random_tag", real_draw)
    for index in range(3):
        create_team(client, name=f"Takım {index + 1}")


def test_two_creations_at_once_at_the_daily_limit_open_one_team(client, storage, monkeypatch):
    first, _second = create_team(client, name="Birinci"), create_team(client, name="İkinci")
    delete_team_rows(storage, first["id"])
    # One captaincy and one place of the day are left. The draw of the tag runs inside the creating write
    # transaction; slowed down, it holds the first creation open while the second one arrives.
    real_draw = teams._random_tag

    def slow_draw():
        time.sleep(0.3)
        return real_draw()

    monkeypatch.setattr(teams, "_random_tag", slow_draw)
    barrier = threading.Barrier(2)
    answers = []

    def create(name):
        barrier.wait(5)
        answers.append(put_team(client, {"name": name}))

    threads = [threading.Thread(target=create, args=(name,)) for name in ("Üçüncü", "Dördüncü")]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(30)
    assert sorted(answer.status_code for answer in answers) == [201, 429]
    refused = next(answer for answer in answers if answer.status_code == 429)
    assert refused.json()["error"]["details"]["scope"] == "TEAMS"
    assert len(rows(storage, "SELECT 1 FROM teams")) == 2 and captaincies(storage) == 2


def test_the_daily_count_starts_again_when_the_server_restarts(storage):
    # The count lives in the memory of the server (design 3.6, C21). The limits that bound what stays on the
    # server (20 memberships and 3 captaincies) are read from the database and hold across a restart.
    with serve(storage, None) as client:
        created = [create_team(client, name=f"Takım {index + 1}") for index in range(3)]
        delete_team_rows(storage, created[0]["id"])
        refused_as(put_team(client, {"name": "Dördüncü"}), 429, "TOO_MANY_ATTEMPTS")
    with serve(storage, None) as client:
        create_team(client, name="Dördüncü")
        refused = refused_as(put_team(client, {"name": "Beşinci"}), 409, "TEAM_LIMIT_REACHED")
        assert refused["details"] == {"scope": "CAPTAIN", "limit": 3}


# --- the tag ------------------------------------------------------------------------------------------

def test_the_tag_ignores_the_client_id(client, storage, monkeypatch):
    real_draw = teams._random_tag
    monkeypatch.setattr(teams, "_random_tag", lambda: "K7Q2")
    first = create_team(client, team_id=str(uuid.UUID(int=1)))
    assert first["tag"] == "K7Q2"
    delete_team_rows(storage, first["id"])
    second = create_team(client, team_id=str(uuid.UUID(int=2 ** 128 - 1)))
    assert second["tag"] == "K7Q2"
    delete_team_rows(storage, second["id"])
    # With the real draw, fifty teams of fifty accounts get fifty different tags of the code alphabet.
    monkeypatch.setattr(teams, "_random_tag", real_draw)
    tags = [create_team(as_account(client, storage, f"test.etiket{index}"), name=f"Takım {index}")["tag"]
            for index in range(50)]
    assert len(set(tags)) == 50
    assert all(len(tag) == 4 and set(tag) <= set(accounts.CODE_ALPHABET) for tag in tags)
    assert sorted(row["tag"] for row in rows(storage, "SELECT tag FROM teams")) == sorted(tags)


def test_the_tag_stays_unique_under_a_forced_rng_collision(client, storage, monkeypatch):
    draws = iter(["AAAA", "AAAA", "BBBB"])
    monkeypatch.setattr(teams, "_random_tag", lambda: next(draws))
    assert create_team(client, name="Birinci")["tag"] == "AAAA"
    assert create_team(client, name="İkinci")["tag"] == "BBBB"
    assert sorted(row["tag"] for row in rows(storage, "SELECT tag FROM teams")) == ["AAAA", "BBBB"]


# --- reading teams ------------------------------------------------------------------------------------

def test_get_teams_lists_only_the_callers_teams_in_joining_order(client, storage, stranger, clock):
    strangers = create_team(stranger, name="Yabancının Takımı")
    clock.advance(hours=1)
    first = create_team(client, name="Sahibin Takımı")
    clock.advance(hours=1)
    seed_member(storage, strangers["id"], OWNER, seat="SUBSTITUTE")
    clock.advance(hours=1)
    last = create_team(client, name="Son Takım")
    # The caller's joining order, not the order in which the teams were created.
    listed = client.get("/v1/teams")
    assert listed.status_code == 200 and set(listed.json()) == {"teams"}
    assert [team["id"] for team in listed.json()["teams"]] == [first["id"], strangers["id"], last["id"]]
    assert [team["myRole"] for team in listed.json()["teams"]] == ["CAPTAIN", "MEMBER", "CAPTAIN"]
    assert listed.json()["teams"][0] == client.get(f"/v1/teams/{first['id']}").json()
    assert [team["id"] for team in stranger.get("/v1/teams").json()["teams"]] == [strangers["id"]]
    assert as_account(client, storage, "test.takimsiz").get("/v1/teams").json() == {"teams": []}


def test_get_team_answers_a_member_and_refuses_a_stranger_403_and_a_missing_team_404(client, storage, stranger):
    team = create_team(client, name="Kuzey Spor", schedule={"weekday": 5, "time": "19:30"})
    seed_member(storage, team["id"], "test.uye", jersey=4)
    member = as_account(client, storage, "test.uye")
    read = member.get(f"/v1/teams/{team['id']}")
    assert read.status_code == 200
    assert (read.json()["name"], read.json()["tag"], read.json()["schedule"]) == (
        "Kuzey Spor", team["tag"], {"weekday": 5, "time": "19:30"})
    assert (read.json()["myRole"], read.json()["mySeat"], read.json()["myJersey"]) == ("MEMBER", "STARTER", 4)
    refused = stranger.get(f"/v1/teams/{team['id']}")
    assert refused_as(refused, 403, "NOT_A_MEMBER")["details"] == {}
    assert "Kuzey Spor" not in refused.text and team["tag"] not in refused.text
    for caller in (client, stranger):
        refused_as(caller.get(f"/v1/teams/{uuid.uuid4()}"), 404, "TEAM_NOT_FOUND")


def test_me_lists_the_callers_teams(client, storage, stranger):
    assert stranger.get("/v1/me").json()["teams"] == []
    team = create_team(client, name="Kuzey Spor")
    me = client.get("/v1/me").json()
    assert me["user"]["username"] == OWNER
    assert me["teams"] == client.get("/v1/teams").json()["teams"] == [client.get(f"/v1/teams/{team['id']}").json()]
    assert stranger.get("/v1/me").json()["teams"] == []


def test_settings_change_the_name_and_the_schedule_and_only_the_captain_may(client, storage):
    team = create_team(client, name="Kuzey Spor", schedule={"weekday": 2, "time": "20:00"})
    seed_member(storage, team["id"], "test.uye")
    member = as_account(client, storage, "test.uye")
    url = f"/v1/teams/{team['id']}/settings"
    read = client.get(f"/v1/teams/{team['id']}").json()
    before = snapshot(storage)
    refused = member.put(url, json={"name": "Üyenin Adı", "schedule": None})
    assert refused_as(refused, 403, "CAPTAIN_ONLY")["details"] == {}
    refused_as(client.put(url, json={"name": "x", "schedule": None}), 422, "NAME_INVALID")
    # The body states both settings: a missing schedule is not read as "clear it".
    refused_as(client.put(url, json={"name": "Güney Spor"}), 422, "VALIDATION_ERROR")
    refused_as(client.put(url, json={"name": "Güney Spor", "schedule": None, "substituteSeats": 3}), 422,
               "VALIDATION_ERROR")
    assert snapshot(storage) == before

    changed = client.put(url, json={"name": "  Güney   Spor ", "schedule": {"weekday": 7, "time": "09:30"}})
    assert changed.status_code == 200, changed.text
    assert (changed.json()["name"], changed.json()["schedule"]) == ("Güney Spor", {"weekday": 7, "time": "09:30"})
    # Only the two settings change: the tag, the seats and the members stay.
    assert {key: value for key, value in changed.json().items() if key not in ("name", "schedule")} == {
        key: value for key, value in read.items() if key not in ("name", "schedule")}
    assert member.get(f"/v1/teams/{team['id']}").json()["name"] == "Güney Spor"
    cleared = client.put(url, json={"name": "Güney Spor", "schedule": None})
    assert cleared.status_code == 200 and cleared.json()["schedule"] is None
    assert rows(storage, "SELECT name, match_weekday, match_time FROM teams WHERE id=?", team["id"]) == [
        {"name": "Güney Spor", "match_weekday": None, "match_time": None}]


def test_the_team_payload_shows_new_joins_and_waiting_to_the_captain_only(client, storage, clock):
    team = create_team(client, name="Kuzey Spor")
    # A member who joined at the captain's mark is not new; members who joined later are.
    seed_member(storage, team["id"], "test.eski", joined_at=team["members"][0]["joinedAt"])
    clock.advance(minutes=5)
    seed_member(storage, team["id"], "test.yeni", jersey=9)
    clock.advance(minutes=5)
    seed_member(storage, team["id"], "test.yedek", seat="SUBSTITUTE")
    captains = client.get(f"/v1/teams/{team['id']}").json()
    assert (captains["newJoins"], captains["seats"]["waiting"]) == (2, 0)
    members = as_account(client, storage, "test.yeni").get(f"/v1/teams/{team['id']}").json()
    assert (members["newJoins"], members["seats"]["waiting"]) == (None, None)
    assert (members["myRole"], members["mySeat"], members["myJersey"]) == ("MEMBER", "STARTER", 9)
    # Everything else is the same for both, in joining order.
    personal = ("myRole", "mySeat", "myJersey", "newJoins", "seats")
    assert {key: value for key, value in members.items() if key not in personal} == {
        key: value for key, value in captains.items() if key not in personal}
    assert [(member["displayName"], member["role"], member["seat"]) for member in captains["members"]] == [
        (f"Test {OWNER}", "CAPTAIN", "STARTER"), ("Test test.eski", "MEMBER", "STARTER"),
        ("Test test.yeni", "MEMBER", "STARTER"), ("Test test.yedek", "MEMBER", "SUBSTITUTE")]
    assert members["seats"] == {"starter": {"seats": 6, "taken": 3, "left": 3, "deciding": 0},
                                "substitute": {"seats": 2, "taken": 1, "left": 1, "deciding": 0},
                                "waiting": None}


def test_one_captain_per_team_holds_in_the_schema(client, storage):
    team = create_team(client, name="Kuzey Spor")
    with pytest.raises(sqlite3.IntegrityError):
        seed_member(storage, team["id"], "test.ikinci.kaptan", role="CAPTAIN")
    assert rows(storage, "SELECT role FROM memberships WHERE team_id=?", team["id"]) == [{"role": "CAPTAIN"}]


# --- the limiter's reservation rule (design 3.6; B6a's signup:src uses it too) -----------------------------

def test_a_limiter_reservation_counts_places_and_gives_back_a_released_one():
    limiter = accounts.Limiter()
    key = "team:user:birinci"
    for second in range(3):
        assert limiter.reserve(key, 3, DAY, "TEAMS", 1000.0 + second) is None
    # A full key waits until its oldest place is a day old. The refusal takes no place.
    assert limiter.reserve(key, 3, DAY, "TEAMS", 1003.0) == (DAY - 3, "TEAMS")
    # A creation that ended without a team gives its place back, and the next one takes it.
    limiter.release(key, 1002.0)
    assert limiter.reserve(key, 3, DAY, "TEAMS", 1004.0) is None
    assert limiter.reserve(key, 3, DAY, "TEAMS", 1005.0) == (DAY - 5, "TEAMS")
    # Another key is not touched, and giving back a place a key does not hold changes nothing.
    assert limiter.reserve("team:user:ikinci", 3, DAY, "TEAMS", 1005.0) is None
    limiter.release(key, 999.0)
    limiter.release("team:user:yok", 1000.0)
    assert limiter.reserve(key, 3, DAY, "TEAMS", 1006.0) == (DAY - 6, "TEAMS")
    # The failure rules do not see a reservation key.
    assert limiter.wait([(key, accounts.BACKOFF, "USER")], 1006.0) is None


def test_a_full_reservation_key_waits_until_its_oldest_place_is_a_window_old():
    limiter = accounts.Limiter()
    key = "team:user:birinci"
    for moment in (1000.0, 2000.0, 3000.0):
        assert limiter.reserve(key, 3, DAY, "TEAMS", moment) is None
    # The wait is rounded up to whole seconds.
    assert limiter.reserve(key, 3, DAY, "TEAMS", 1000.0 + DAY - 0.5) == (1, "TEAMS")
    assert limiter.reserve(key, 3, DAY, "TEAMS", 1000.0 + DAY) is None
    assert limiter.reserve(key, 3, DAY, "TEAMS", 2000.0 + DAY - 10) == (10, "TEAMS")


def test_a_full_reservation_key_is_never_forgotten_to_make_room():
    limiter = accounts.Limiter(max_keys=3)
    full = "team:user:dolu"
    for second in range(3):
        limiter.reserve(full, 3, DAY, "TEAMS", 100.0 + second)
    for name in ("b", "c", "d", "e"):
        limiter.fail(accounts.login_keys(f"user.{name}", "local"), 110.0)
    assert len(limiter) <= 3
    assert limiter.reserve(full, 3, DAY, "TEAMS", 120.0) == (DAY - 20, "TEAMS")

    # A key that is not full counts its places of the window as recent: a flood of new keys forgets a key
    # with fewer recent failures first.
    limiter = accounts.Limiter(max_keys=2)
    two = "team:user:iki"
    limiter.reserve(two, 3, DAY, "TEAMS", 100.0)
    limiter.reserve(two, 3, DAY, "TEAMS", 101.0)
    for index in range(5):
        limiter.fail([(f"login:user:junk.{index}:local", accounts.BACKOFF, "USER")], 110.0 + index)
    assert limiter.reserve(two, 3, DAY, "TEAMS", 120.0) is None
    assert limiter.reserve(two, 3, DAY, "TEAMS", 121.0) == (DAY - 21, "TEAMS")

    # When every slot holds a locked key, a new key is not tracked: its place is given and nothing is forgotten.
    crowded = accounts.Limiter(max_keys=1)
    assert crowded.reserve("team:user:a", 1, DAY, "TEAMS", 100.0) is None
    assert crowded.reserve("team:user:b", 1, DAY, "TEAMS", 101.0) is None
    assert len(crowded) == 1
    assert crowded.reserve("team:user:a", 1, DAY, "TEAMS", 102.0) == (DAY - 2, "TEAMS")
