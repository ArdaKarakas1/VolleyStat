"""Sessions and access on every route (docs/API_V1.md, section 10; ADR-0119, B3).

The walk below lists every route the app serves with the session decision it makes. A route a later
slice adds without a row here fails the first test, so no route ships without that decision.
"""
import sqlite3
import uuid
from contextlib import closing
from dataclasses import dataclass, field
from typing import Any

import pytest
from fastapi.routing import APIRoute
from starlette.routing import Route as StarletteRoute

from app import access, accounts, media
from app.access import READ, REVIEW
from app.bodylimit import MAX_JSON_BODY_BYTES
from tests.helpers import (
    NO_SESSION, OWNER, STRANGER, As, ScriptedAnalyzer, as_account, bearer, card_of, create_team, decide, execute,
    ready_video, request_job, rows, run_pending, seed_member, serve, session_for, user_id_of,
)
from tests.test_api import create_match, create_upload
from tests.test_media import store

PUBLIC = "PUBLIC"
WRONG_SHAPE = {"json": {"unexpected": True}}
KEY = "walk-" + str(uuid.uuid4())
MATCH_BODY = {"homeTeamName": "Erişim Ev", "awayTeamName": "Erişim Konuk"}
OTHER_RULE = {"setsToWin": 2, "setTarget": 21, "decidingSetTarget": 15}
UPLOAD_BODY = {"fileName": "mac.mp4", "sizeBytes": 10, "mimeType": "video/mp4", "rightsAttested": True,
               "allowTraining": False}
TEAM_BODY = {"name": "Erişim Takımı"}
# The access roles of the team routes: any member, or the captain only.
MEMBER, CAPTAIN = "MEMBER", "CAPTAIN"
# The seeded member of the world's team (a substitute), whose session the captain-only walk uses.
TEAM_MEMBER = "test.takim.uyesi"


@dataclass(frozen=True)
class Route:
    """How the walk calls a route. `kind` is the object the path names (None: the caller's own account,
    or a new match) and `role` the access role the route checks (READ or REVIEW on a match; MEMBER or
    CAPTAIN on a team). The valid request is stale where the route checks a version, an offset or a bound,
    so only the access check stands between a stranger and an answer that names something. `bad` is a
    request whose body, query or header has the wrong form."""
    kind: str | None
    role: str | None
    json: Any = None
    content: bytes | None = None
    params: dict | None = None
    headers: dict | None = None
    bad: dict = field(default_factory=dict)

    def valid(self) -> dict[str, Any]:
        options = {"json": self.json, "content": self.content, "params": self.params, "headers": self.headers}
        return {name: value for name, value in options.items() if value is not None}


OWN_ACCOUNT = Route(None, None)

WALK: dict[tuple[str, str], Any] = {
    ("GET", "/v1/health"): PUBLIC,
    ("POST", "/v1/auth/register"): PUBLIC,
    ("POST", "/v1/auth/login"): PUBLIC,
    ("POST", "/v1/auth/logout"): Route(None, None, json={}, bad=WRONG_SHAPE),
    ("GET", "/v1/me"): OWN_ACCOUNT,
    ("PUT", "/v1/me/display-name"): Route(None, None, json={"displayName": "Yeni Ad"}, bad=WRONG_SHAPE),
    ("PUT", "/v1/me/password"): Route(None, None, json={"currentPassword": "x" * 12, "newPassword": "y" * 12},
                                      bad=WRONG_SHAPE),
    ("POST", "/v1/me/delete"): Route(None, None, json={"password": "x" * 12}, bad=WRONG_SHAPE),
    ("POST", "/v1/matches"): Route(None, None, json=MATCH_BODY, bad=WRONG_SHAPE),
    ("PUT", "/v1/matches/{match_id}"): Route("match", READ, json=MATCH_BODY, bad=WRONG_SHAPE),
    ("PUT", "/v1/matches/{match_id}/rules"): Route("match", REVIEW, json={**OTHER_RULE, "expectedRules": OTHER_RULE},
                                                   bad=WRONG_SHAPE),
    ("GET", "/v1/matches/{match_id}"): Route("match", READ),
    ("POST", "/v1/matches/{match_id}/uploads"): Route("match", REVIEW, json=UPLOAD_BODY, bad=WRONG_SHAPE),
    ("GET", "/v1/uploads/{upload_id}"): Route("upload", REVIEW),
    ("PUT", "/v1/uploads/{upload_id}/chunks"): Route("upload", REVIEW, content=b"x" * 10, params={"offset": 999},
                                                     bad={"content": b"x" * 10}),
    ("POST", "/v1/uploads/{upload_id}/complete"): Route("upload", REVIEW),
    ("DELETE", "/v1/uploads/{upload_id}"): Route("upload", REVIEW),
    ("GET", "/v1/videos/{video_id}"): Route("video", READ),
    ("GET", "/v1/videos/{video_id}/quality"): Route("video", READ),
    ("POST", "/v1/videos/{video_id}/quality"): Route("video", REVIEW),
    ("GET", "/v1/videos/{video_id}/content"): Route("video", READ, headers={"Range": "bytes=0-9"}),
    ("DELETE", "/v1/videos/{video_id}"): Route("video", REVIEW),
    ("DELETE", "/v1/matches/{match_id}"): Route("match", REVIEW),
    ("POST", "/v1/videos/{video_id}/analyses"): Route("video", REVIEW, headers={"Idempotency-Key": KEY},
                                                      bad={"headers": {"Idempotency-Key": ""}}),
    ("GET", "/v1/videos/{video_id}/analyses/latest"): Route("video", READ),
    ("GET", "/v1/analyses/{job_id}"): Route("job", READ),
    ("POST", "/v1/analyses/{job_id}/cancel"): Route("job", REVIEW),
    ("GET", "/v1/analyses/{job_id}/rallies"): Route("job", READ),
    ("GET", "/v1/analyses/{job_id}/review-cards"): Route("job", READ, params={"scope": "ALL"},
                                                         bad={"params": {"scope": "WRONG"}}),
    ("POST", "/v1/review-cards/{card_id}/decisions"): Route(
        "card", REVIEW, json={"selection": "HOME", "expectedVersion": 999, "idempotencyKey": KEY}, bad=WRONG_SHAPE),
    ("POST", "/v1/review-cards/{card_id}/reopen"): Route("card", REVIEW, json={"expectedVersion": 999}, bad=WRONG_SHAPE),
    ("GET", "/v1/matches/{match_id}/report"): Route("match", READ),
    ("POST", "/v1/analyses/{job_id}/rallies"): Route("job", REVIEW, json={"startMs": 0, "endMs": 50, "expectedListVersion": 999},
                                                     bad=WRONG_SHAPE),
    ("POST", "/v1/rallies/{rally_id}/move"): Route("rally", REVIEW, json={"startMs": 300, "endMs": 460, "expectedListVersion": 999},
                                                   bad=WRONG_SHAPE),
    ("POST", "/v1/rallies/{rally_id}/split"): Route("rally", REVIEW, json={"atMs": 999_999, "expectedListVersion": 999},
                                                    bad=WRONG_SHAPE),
    ("POST", "/v1/rallies/{rally_id}/merge-previous"): Route("rally", REVIEW, json={"expectedListVersion": 999},
                                                             bad=WRONG_SHAPE),
    ("POST", "/v1/analyses/{job_id}/rally-list/confirm"): Route("job", REVIEW, json={"expectedListVersion": 999},
                                                                bad=WRONG_SHAPE),
    ("GET", "/v1/teams"): OWN_ACCOUNT,
    # A new id creates a team; another account's id answers 409 TEAM_ID_TAKEN (design 4.1), not 403.
    ("PUT", "/v1/teams/{team_id}"): Route("team", MEMBER, json=TEAM_BODY, bad=WRONG_SHAPE),
    ("GET", "/v1/teams/{team_id}"): Route("team", MEMBER),
    ("PUT", "/v1/teams/{team_id}/settings"): Route("team", CAPTAIN, json={"name": "Yeni Ad", "schedule": None},
                                                   bad=WRONG_SHAPE),
    # Stale: 0 substitute seats while the world's team has a substitute, so the captain gets SEATS_BELOW_HOLDERS.
    ("PUT", "/v1/teams/{team_id}/seats"): Route("team", CAPTAIN, json={"substituteSeats": 0}, bad=WRONG_SHAPE),
}
NOT_FOUND = {"match": "MATCH_NOT_FOUND", "upload": "UPLOAD_NOT_FOUND", "video": "VIDEO_NOT_FOUND",
             "job": "ANALYSIS_NOT_FOUND", "card": "CARD_NOT_FOUND", "rally": "RALLY_NOT_FOUND",
             "team": "TEAM_NOT_FOUND"}
SESSION_ROUTES = {operation: route for operation, route in WALK.items() if route is not PUBLIC}
OBJECT_ROUTES = {operation: route for operation, route in SESSION_ROUTES.items() if route.kind is not None}


def served_operations(app) -> set[tuple[str, str]]:
    """Every (method, path) the app answers. FastAPI 0.16x keeps each included router as one route entry
    (`original_router`, `include_context.prefix`); older versions flatten them, and both are read. A route
    of any other kind (a mounted folder or app, a websocket, a host) fails the walk with its path: it has
    no methods and no row in app.openapi(), so without this it would answer without a decision here."""
    def walk(routes, prefix=""):
        for route in routes:
            inner = getattr(route, "original_router", None)
            if inner is not None:
                yield from walk(inner.routes, prefix + route.include_context.prefix)
            elif isinstance(route, (APIRoute, StarletteRoute)):
                for method in route.methods or ():
                    yield method, prefix + route.path
            else:
                raise AssertionError(f"The walk can not classify {type(route).__name__} at "
                                     f"{prefix + getattr(route, 'path', '?')}: give it a row in WALK or in access.PUBLIC")
    return set(walk(app.routes))


# A request may record the use of its session. The sweep thread writes its uptime (server_clock) on its own
# schedule: a thread that starts 20 ms late writes it for the first time between two snapshots of a test.
NOT_SNAPSHOT = {"sessions", "server_clock"}


def snapshot(storage) -> tuple[dict[str, list[tuple]], list[tuple[str, int]]]:
    """Every table but those in NOT_SNAPSHOT, and every stored file with its size."""
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db:
        tables = [name for (name,) in db.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
                  if name not in NOT_SNAPSHOT]
        dump = {table: db.execute(f"SELECT * FROM {table} ORDER BY rowid").fetchall() for table in tables}
    files = sorted((str(path.relative_to(storage)), path.stat().st_size) for path in storage.rglob("*")
                   if path.is_file() and not path.name.startswith("volleystat.sqlite3"))
    return dump, files


@dataclass
class World:
    owner: Any
    stranger: As
    ids: dict[str, str]
    storage: Any
    member: As

    def url(self, path, **override):
        return path.format(**{**self.ids, **override})


@pytest.fixture
def world(storage, make_clip):
    """The owner's personal match with a READY video, a NEEDS_REVIEW job, its cards and rallies, and an
    unfinished upload. The owner also captains a team with a member on a substitute seat. The stranger owns a
    personal match and a team of its own."""
    with serve(storage, ScriptedAnalyzer()) as owner:
        video = ready_video(owner, make_clip)
        job = request_job(owner, video["id"])
        assert run_pending(owner) == 1
        rallies = owner.get(f"/v1/analyses/{job['id']}/rallies").json()["rallies"]
        upload = create_upload(owner, video["matchId"], size=10)
        assert upload.status_code == 201, upload.text
        ids = {"match_id": video["matchId"], "upload_id": upload.json()["uploadId"], "video_id": video["id"],
               "job_id": job["id"], "card_id": card_of(owner, job["id"], 2)["id"], "rally_id": rallies[1]["id"]}
        team = create_team(owner, name="Sahibin Takımı")
        ids.update(team_id=team["id"], user_id=seed_member(storage, team["id"], TEAM_MEMBER, seat="SUBSTITUTE"))
        stranger = as_account(owner, storage, STRANGER)
        assert stranger.post("/v1/matches", json=MATCH_BODY).status_code == 201
        create_team(stranger, name="Yabancının Takımı")
        yield World(owner, stranger, ids, storage, as_account(owner, storage, TEAM_MEMBER))


def refused_as(response, status, code):
    assert response.status_code == status, (response.request.method, response.request.url, response.text)
    assert response.json()["error"]["code"] == code, response.text
    return response.json()["error"]


def ended_session_token(storage):
    token = session_for(storage, "test.bitmis")
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
        db.execute("UPDATE sessions SET expires_at='2020-01-01T00:00:00Z' WHERE token_hash=?", (accounts.digest(token),))
    return token


def deleted_account_token(storage):
    token = session_for(storage, "test.silinmis")
    with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("DELETE FROM users WHERE username='test.silinmis'")
    return token


# --- the walk ---------------------------------------------------------------------------------------

def test_the_walk_lists_every_route_of_the_app(storage):
    with serve(storage, None) as client:
        app = client.app
        assert served_operations(app) == set(WALK)
        # The public rows are exactly the routes the access module names public.
        assert {operation for operation, route in WALK.items() if route is PUBLIC} == access.PUBLIC
        assert {(method.upper(), path) for path, operations in app.openapi()["paths"].items()
                for method in operations} == set(WALK)
    assert len(WALK) == 42


@pytest.mark.parametrize("extra", ["mount", "websocket", "sub-app"])
def test_the_walk_names_a_route_it_can_not_classify(storage, extra):
    """A mounted folder or app and a websocket route have no methods and no row in app.openapi(). The walk
    fails on them instead of passing them by, so each needs a decision here or in access.PUBLIC."""
    from fastapi import FastAPI
    from fastapi.staticfiles import StaticFiles
    from app.main import create_app

    app = create_app(storage, analyzer="none", start_worker=False)
    (storage / "pub").mkdir()
    if extra == "mount":
        app.mount("/v1/files", StaticFiles(directory=storage / "pub"))
    elif extra == "sub-app":
        app.mount("/v1/files", FastAPI())
    else:
        @app.websocket("/v1/files")
        async def live(socket):  # pragma: no cover - never connected
            await socket.close()
    with pytest.raises(AssertionError, match="/v1/files"):
        served_operations(app)


def test_every_route_but_the_public_ones_answers_401_without_a_session(world):
    storage = world.storage
    credentials = [NO_SESSION, bearer("nonsense"), {"Authorization": "Basic eA=="},
                   bearer(ended_session_token(storage)), bearer(deleted_account_token(storage))]
    before = snapshot(storage)
    for (method, path), route in SESSION_ROUTES.items():
        for headers in credentials:
            options = dict(route.bad)
            options["headers"] = {**options.get("headers", {}), **headers}
            response = world.owner.request(method, world.url(path), **options)
            refused_as(response, 401, "AUTH_REQUIRED")
            assert response.headers["www-authenticate"] == "Bearer"
    assert snapshot(storage) == before


def test_every_route_answers_a_stranger_403_not_a_member_and_changes_nothing(world):
    before = snapshot(world.storage)
    for (method, path), route in OBJECT_ROUTES.items():
        if (method, path) == ("PUT", "/v1/teams/{team_id}"):
            continue  # another account's team id answers 409 (test_put_of_another_accounts_team_id_...)
        error = refused_as(world.stranger.request(method, world.url(path), **route.valid()), 403, "NOT_A_MEMBER")
        assert error["details"] == {}
    assert snapshot(world.storage) == before


def test_every_route_of_a_legacy_match_answers_403_even_to_its_former_owner(world):
    with closing(sqlite3.connect(world.storage / "volleystat.sqlite3")) as db, db:
        db.execute("UPDATE matches SET status='LEGACY', owner_user_id=NULL WHERE id=?", (world.ids["match_id"],))
    before = snapshot(world.storage)
    for (method, path), route in OBJECT_ROUTES.items():
        if route.kind == "team":
            continue  # a team has no LEGACY state, and the owner still captains the world's team
        error = refused_as(world.owner.request(method, world.url(path), **route.valid()), 403, "NOT_A_MEMBER")
        assert error["details"] == {}
    # A repeated PUT of the id and the deletion of the match are refused too.
    refused_as(world.owner.put(world.url("/v1/matches/{match_id}"), json=MATCH_BODY), 403, "NOT_A_MEMBER")
    refused_as(world.owner.delete(world.url("/v1/matches/{match_id}")), 403, "NOT_A_MEMBER")
    assert snapshot(world.storage) == before


def test_a_missing_object_answers_404_with_its_own_code(world):
    missing = str(uuid.uuid4())
    before = snapshot(world.storage)
    for caller in (world.owner, world.stranger):
        for (method, path), route in OBJECT_ROUTES.items():
            if (method, path) in (("PUT", "/v1/matches/{match_id}"), ("PUT", "/v1/teams/{team_id}")):
                continue  # a new id creates the match or the team
            url = world.url(path, **{name: missing for name in world.ids})
            error = refused_as(caller.request(method, url, **route.valid()), 404, NOT_FOUND[route.kind])
            assert error["code"] == NOT_FOUND[route.kind]
    assert snapshot(world.storage) == before


def test_every_captain_route_answers_a_member_403_captain_only_and_changes_nothing(world):
    captain_routes = {operation: route for operation, route in OBJECT_ROUTES.items() if route.role == CAPTAIN}
    assert set(captain_routes) == {("PUT", "/v1/teams/{team_id}/settings"), ("PUT", "/v1/teams/{team_id}/seats")}
    before = snapshot(world.storage)
    for (method, path), route in captain_routes.items():
        error = refused_as(world.member.request(method, world.url(path), **route.valid()), 403, "CAPTAIN_ONLY")
        assert error["details"] == {}
    # The member still reads the team, and the stranger is no member at all.
    assert world.member.get(world.url("/v1/teams/{team_id}")).status_code == 200
    for (method, path), route in captain_routes.items():
        refused_as(world.stranger.request(method, world.url(path), **route.valid()), 403, "NOT_A_MEMBER")
    assert snapshot(world.storage) == before


def test_the_captain_gets_the_answer_a_stale_seats_body_hides_from_others(world):
    # The walk's seats body is stale: only access stands between the member or the stranger and these numbers.
    refused = world.owner.put(world.url("/v1/teams/{team_id}/seats"), json={"substituteSeats": 0})
    error = refused_as(refused, 409, "SEATS_BELOW_HOLDERS")
    assert error["details"] == {"seat": "SUBSTITUTE", "holders": 1, "seats": 0}


def test_put_of_another_accounts_team_id_answers_409_team_id_taken_and_creates_nothing(world):
    team_id = world.ids["team_id"]
    before = snapshot(world.storage)
    refused = world.stranger.put(f"/v1/teams/{team_id}", json={"name": "Başkasının Takımı"})
    assert refused_as(refused, 409, "TEAM_ID_TAKEN")["details"] == {}
    assert "Sahibin Takımı" not in refused.text
    assert snapshot(world.storage) == before
    # The member repeats the id and gets the team back, as its captain does.
    assert world.member.put(f"/v1/teams/{team_id}", json=TEAM_BODY).status_code == 200
    assert world.owner.put(f"/v1/teams/{team_id}", json=TEAM_BODY).status_code == 200
    assert snapshot(world.storage) == before
    # A malformed id is refused before anything is looked up.
    refused_as(world.stranger.put("/v1/teams/NOT-A-UUID", json=TEAM_BODY), 422, "TEAM_ID_INVALID")


def test_the_owner_reaches_every_reading_route(world):
    for (method, path), route in OBJECT_ROUTES.items():
        if method == "GET":
            response = world.owner.request(method, world.url(path), **route.valid())
            assert response.status_code in (200, 206), (path, response.text)


def test_an_unparseable_or_oversized_body_is_refused_before_the_session_and_changes_nothing(world):
    anonymous = As(world.owner, NO_SESSION)
    before = snapshot(world.storage)
    for path in ("/v1/matches", world.url("/v1/review-cards/{card_id}/decisions")):
        broken = anonymous.post(path, content=b"{x", headers={"Content-Type": "application/json"})
        error = refused_as(broken, 422, "VALIDATION_ERROR")
        assert [entry["location"] for entry in error["details"]["fields"]] == ["body.1"]
        large = anonymous.post(path, content=b"x" * (MAX_JSON_BODY_BYTES + 1))
        error = refused_as(large, 413, "PAYLOAD_TOO_LARGE")
        assert error["details"] == {"maxBytes": MAX_JSON_BODY_BYTES}
        # Neither answer names an object of the server.
        for response in (broken, large):
            assert all(value not in response.text for value in world.ids.values())
    assert snapshot(world.storage) == before


def test_the_routes_of_the_own_account_answer_the_caller(world):
    me = world.stranger.get("/v1/me")
    assert me.status_code == 200 and me.json()["user"]["username"] == STRANGER
    assert world.owner.get("/v1/me").json()["user"]["username"] == OWNER


# --- creating and repeating a match -----------------------------------------------------------------

def match_row(storage, match_id):
    found = rows(storage, "SELECT status, owner_user_id FROM matches WHERE id=?", match_id)
    return found[0] if found else None


def test_post_and_put_create_a_personal_match_of_the_caller(client, storage, stranger):
    created = client.post("/v1/matches", json=MATCH_BODY)
    assert created.status_code == 201 and created.json()["status"] == "PERSONAL"
    assert match_row(storage, created.json()["id"]) == {"status": "PERSONAL", "owner_user_id": user_id_of(storage)}
    match_id = str(uuid.uuid4())
    put = stranger.put(f"/v1/matches/{match_id}", json=MATCH_BODY)
    assert put.status_code == 201 and put.json()["status"] == "PERSONAL"
    assert match_row(storage, match_id) == {"status": "PERSONAL", "owner_user_id": user_id_of(storage, STRANGER)}
    # The creator repeats the PUT: the same match, nothing changed.
    again = stranger.put(f"/v1/matches/{match_id}", json={**MATCH_BODY, "venue": "Başka salon"})
    assert again.status_code == 200 and again.json() == put.json()
    assert client.get(f"/v1/matches/{match_id}").status_code == 403


def test_put_of_another_accounts_match_id_answers_403_and_creates_nothing(client, storage, stranger):
    match_id = str(uuid.uuid4())
    assert client.put(f"/v1/matches/{match_id}", json=MATCH_BODY).status_code == 201
    before = snapshot(storage)
    refused = stranger.put(f"/v1/matches/{match_id}", json={**MATCH_BODY, "name": "Başkasının maçı"})
    assert refused_as(refused, 403, "NOT_A_MEMBER")["details"] == {}
    assert snapshot(storage) == before
    # A malformed id is refused before anything is looked up, as before.
    refused_as(stranger.put("/v1/matches/NOT-A-UUID", json=MATCH_BODY), 422, "MATCH_ID_INVALID")


# --- a session that ends while a route works -------------------------------------------------------

def test_a_write_whose_session_ended_in_between_writes_nothing(client, storage, monkeypatch):
    match_id = create_match(client)
    before = snapshot(storage)
    original = accounts.live_session
    calls = []

    def read_then_end(db, hashed, moment):
        row = original(db, hashed, moment)
        if not calls:
            calls.append(hashed)
            # The session ends right after the dependency read it, before the route's write transaction.
            with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as other, other:
                other.execute("DELETE FROM sessions WHERE token_hash=?", (hashed,))
        return row

    for request in (lambda: client.put(f"/v1/matches/{uuid.uuid4()}", json=MATCH_BODY),
                    lambda: create_upload(client, match_id, size=10)):
        calls.clear()
        monkeypatch.setattr(accounts, "live_session", read_then_end)
        refused_as(request(), 401, "AUTH_REQUIRED")
        monkeypatch.setattr(accounts, "live_session", original)
        client.headers.update(bearer(session_for(storage)))
    assert snapshot(storage)[0]["matches"] == before[0]["matches"]
    assert snapshot(storage)[0]["uploads"] == before[0]["uploads"]
    assert snapshot(storage)[1] == before[1]


def test_every_write_route_reads_the_session_again_in_its_write_transaction(world, monkeypatch):
    original = accounts.live_session
    ended = []

    def read_then_end(db, hashed, moment):
        row = original(db, hashed, moment)
        if not ended:
            ended.append(hashed)
            with closing(sqlite3.connect(world.storage / "volleystat.sqlite3")) as other, other:
                other.execute("DELETE FROM sessions WHERE token_hash=?", (hashed,))
        return row

    monkeypatch.setattr(accounts, "live_session", read_then_end)
    writes = {**{key: route for key, route in OBJECT_ROUTES.items() if key[0] != "GET"},
              ("POST", "/v1/matches"): SESSION_ROUTES[("POST", "/v1/matches")]}
    for (method, path), route in writes.items():
        ended.clear()
        world.owner.headers.update(bearer(session_for(world.storage)))
        before = snapshot(world.storage)
        response = world.owner.request(method, world.url(path), **route.valid())
        if (method, path) == ("POST", "/v1/videos/{video_id}/quality"):
            # A READY video needs no new check: the route answers from its read transaction and writes nothing.
            assert response.status_code == 200, response.text
        else:
            refused_as(response, 401, "AUTH_REQUIRED")
        assert ended, path
        assert snapshot(world.storage) == before, path


def test_a_failed_verification_after_the_session_ended_still_reopens_the_upload(storage, monkeypatch):
    with serve(storage, None, raise_server_exceptions=False) as client:
        upload_id = create_upload(client, create_match(client), size=5).json()["uploadId"]
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde").status_code == 200
        touched = rows(storage, "SELECT touched_at FROM uploads WHERE id=?", upload_id)[0]["touched_at"]
        execute(storage, "UPDATE uploads SET touched_uptime=NULL WHERE id=?", upload_id)

        def end_session_and_fail(path, mime):
            with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
                db.execute("DELETE FROM sessions")
            raise RuntimeError("the probe broke")

        monkeypatch.setattr(media, "probe_video", end_session_and_fail)
        failed = client.post(f"/v1/uploads/{upload_id}/complete")
        # The original error, not a 401: the route only undoes its own mark there.
        assert failed.status_code == 500
        row = rows(storage, "SELECT status, finalize_started_at, finalize_started_uptime, finalize_token, touched_at,"
                   " touched_uptime FROM uploads WHERE id=?", upload_id)[0]
        assert (row["status"], row["finalize_started_at"], row["finalize_started_uptime"], row["finalize_token"]) == (
            "UPLOADING", None, None, None)
        # The reopen is a touch: the sweep waits a day of uptime from it (the stored uptime, 0: no sweep runs).
        assert row["touched_at"] > touched and row["touched_uptime"] == 0.0


def test_a_verification_whose_session_ended_answers_401_and_reopens_the_upload(storage, monkeypatch):
    with serve(storage, None) as client:
        upload_id = create_upload(client, create_match(client), size=5).json()["uploadId"]
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde").status_code == 200
        original = media.probe_video

        def end_session_while_probing(path, mime):
            result = original(path, mime)
            with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
                db.execute("DELETE FROM sessions")
            return result

        monkeypatch.setattr(media, "probe_video", end_session_while_probing)
        refused_as(client.post(f"/v1/uploads/{upload_id}/complete"), 401, "AUTH_REQUIRED")
        # No video, and the upload is open again for the account's next session.
        assert rows(storage, "SELECT 1 FROM videos") == []
        row = rows(storage, "SELECT status, finalize_started_at, finalize_token FROM uploads WHERE id=?", upload_id)[0]
        assert (row["status"], row["finalize_started_at"], row["finalize_token"]) == ("UPLOADING", None, None)


def test_a_verification_whose_match_changed_hands_stores_no_video(storage, monkeypatch):
    # Nothing in B3 moves a personal match to another account, but the final transaction of complete
    # checks the access again after the long work outside a transaction, as every later slice needs.
    with serve(storage, None) as client:
        match_id = create_match(client)
        upload_id = create_upload(client, match_id, size=5).json()["uploadId"]
        assert client.put(f"/v1/uploads/{upload_id}/chunks?offset=0", content=b"abcde").status_code == 200
        original = media.probe_video

        def hand_over_while_probing(path, mime):
            result = original(path, mime)
            with closing(sqlite3.connect(storage / "volleystat.sqlite3")) as db, db:
                db.execute("UPDATE matches SET status='LEGACY', owner_user_id=NULL WHERE id=?", (match_id,))
            return result

        monkeypatch.setattr(media, "probe_video", hand_over_while_probing)
        refused_as(client.post(f"/v1/uploads/{upload_id}/complete"), 403, "NOT_A_MEMBER")
        assert rows(storage, "SELECT 1 FROM videos") == []
        assert rows(storage, "SELECT status FROM uploads WHERE id=?", upload_id) == [{"status": "UPLOADING"}]


# --- what a stranger never learns ------------------------------------------------------------------

def test_a_stranger_never_learns_a_current_version_or_offset(world, make_clip):
    owner, stranger, url = world.owner, world.stranger, world.url
    version = owner.get(url("/v1/analyses/{job_id}/rallies")).json()["rallyListVersion"]
    rally = owner.get(url("/v1/analyses/{job_id}/rallies")).json()["rallies"][1]
    quarantined = store(owner, b"not a video at all", match_id=world.ids["match_id"])
    assert owner.put(url("/v1/uploads/{upload_id}/chunks"), params={"offset": 0}, content=b"abc").status_code == 200
    cases = [
        ("POST", url("/v1/review-cards/{card_id}/decisions"), {"json": {"selection": "HOME", "expectedVersion": 99}},
         "REVIEW_STALE_VERSION"),
        ("POST", url("/v1/analyses/{job_id}/rallies"), {"json": {"startMs": 0, "endMs": 50, "expectedListVersion": version + 5}},
         "RALLY_LIST_STALE"),
        ("PUT", url("/v1/matches/{match_id}/rules"), {"json": {**OTHER_RULE, "expectedRules": OTHER_RULE}},
         "MATCH_RULES_STALE"),
        ("PUT", url("/v1/uploads/{upload_id}/chunks"), {"params": {"offset": 1}, "content": b"x"}, "OFFSET_MISMATCH"),
        ("POST", url("/v1/analyses/{job_id}/cancel"), {}, "JOB_FINISHED"),
        ("POST", f"/v1/videos/{quarantined['id']}/analyses", {}, "VIDEO_NOT_READY"),
        ("GET", f"/v1/videos/{quarantined['id']}/content", {}, "VIDEO_NOT_READY"),
        ("POST", url("/v1/rallies/{rally_id}/split"), {"json": {"atMs": rally["endMs"] + 10, "expectedListVersion": version}},
         "SPLIT_POINT_OUTSIDE"),
    ]
    for method, path, options, code in cases:
        before = snapshot(world.storage)
        error = refused_as(stranger.request(method, path, **options), 403, "NOT_A_MEMBER")
        assert error["details"] == {}
        assert snapshot(world.storage) == before
        # The owner's answer shows what the stranger would have learned.
        owners = owner.request(method, path, **options)
        assert owners.json()["error"]["code"] == code, owners.text
        assert owners.json()["error"]["details"] != {}, owners.text


def test_the_api_docs_routes_are_off(client, anonymous):
    for path in ("/docs", "/docs/oauth2-redirect", "/redoc", "/openapi.json"):
        for caller in (client, anonymous):
            response = caller.get(path)
            assert response.status_code == 404, path
            assert response.json()["error"]["code"] == "NOT_FOUND"
    # The committed document is still built from the app (tests/test_openapi.py).
    assert client.app.openapi()["info"]["version"] == "0.10.0"


# --- the decision replay ----------------------------------------------------------------------------

def decisions(storage):
    return rows(storage, "SELECT id, card_id, idempotency_key FROM review_decisions ORDER BY rowid")


def test_a_replayed_key_returns_the_stored_answer_only_to_its_reviewer(world):
    url = world.url("/v1/review-cards/{card_id}/decisions")
    card = card_of(world.owner, world.ids["job_id"], 2)
    first = decide(world.owner, card, "HOME", idempotencyKey="tek-dokunus")
    before = snapshot(world.storage)
    body = {"selection": "HOME", "expectedVersion": card["version"], "idempotencyKey": "tek-dokunus"}
    replayed = world.owner.post(url, json=body)
    assert replayed.status_code == 200 and replayed.json() == first
    assert snapshot(world.storage) == before
    refused = world.stranger.post(url, json=body)
    assert refused_as(refused, 403, "NOT_A_MEMBER")["details"] == {}
    assert first["decisionId"] not in refused.text and world.ids["job_id"] not in refused.text
    assert snapshot(world.storage) == before


@pytest.fixture
def strangers_card(world, make_clip):
    video = ready_video(world.stranger, make_clip)
    job = request_job(world.stranger, video["id"])
    assert run_pending(world.owner) == 1
    return card_of(world.stranger, job["id"], 1)


def test_a_key_of_another_account_on_the_callers_own_card_answers_403_and_decides_nothing(world, strangers_card):
    card = card_of(world.owner, world.ids["job_id"], 2)
    decide(world.owner, card, "HOME", idempotencyKey="sahibin-anahtari")
    before = snapshot(world.storage)
    refused = world.stranger.post(f"/v1/review-cards/{strangers_card['id']}/decisions",
                                  json={"selection": "AWAY", "expectedVersion": strangers_card["version"],
                                        "idempotencyKey": "sahibin-anahtari"})
    assert refused_as(refused, 403, "NOT_A_MEMBER")["details"] == {}
    assert snapshot(world.storage) == before
    # A key of its own still decides the stranger's card.
    decided = world.stranger.post(f"/v1/review-cards/{strangers_card['id']}/decisions",
                                  json={"selection": "AWAY", "expectedVersion": strangers_card["version"],
                                        "idempotencyKey": "yabancinin-anahtari"})
    assert decided.status_code == 200 and decided.json()["card"]["answer"] == "AWAY"


def test_a_replay_on_a_match_that_became_legacy_answers_403(world):
    card = card_of(world.owner, world.ids["job_id"], 2)
    decide(world.owner, card, "HOME", idempotencyKey="eski-anahtar")
    with closing(sqlite3.connect(world.storage / "volleystat.sqlite3")) as db, db:
        db.execute("UPDATE matches SET status='LEGACY', owner_user_id=NULL WHERE id=?", (world.ids["match_id"],))
    replayed = world.owner.post(world.url("/v1/review-cards/{card_id}/decisions"),
                                json={"selection": "HOME", "expectedVersion": card["version"], "idempotencyKey": "eski-anahtar"})
    assert refused_as(replayed, 403, "NOT_A_MEMBER")["details"] == {}


def test_a_replay_whose_card_a_later_decision_removed_still_answers_its_reviewer(world):
    # The stored row decides, also when its card is gone: a winner change removes the action card.
    card = card_of(world.owner, world.ids["job_id"], 2)
    decide(world.owner, card, "HOME")
    action = card_of(world.owner, world.ids["job_id"], 2, "TERMINAL_ACTION")
    stored = decide(world.owner, action, "ATTACK", idempotencyKey="silinen-kart")
    decide(world.owner, card_of(world.owner, world.ids["job_id"], 2), "AWAY")
    assert card_of(world.owner, world.ids["job_id"], 2, "TERMINAL_ACTION")["answer"] is None
    body = {"selection": "ATTACK", "expectedVersion": action["version"], "idempotencyKey": "silinen-kart"}
    replayed = world.owner.post(f"/v1/review-cards/{action['id']}/decisions", json=body)
    assert replayed.status_code == 200 and replayed.json() == stored
    refused_as(world.stranger.post(f"/v1/review-cards/{action['id']}/decisions", json=body), 403, "NOT_A_MEMBER")


def test_an_analysis_key_of_another_account_returns_no_job(world, make_clip):
    owners = world.owner.post(world.url("/v1/videos/{video_id}/analyses"), headers={"Idempotency-Key": "analiz-anahtari"})
    assert owners.status_code in (200, 202)
    refused = world.stranger.post(world.url("/v1/videos/{video_id}/analyses"), headers={"Idempotency-Key": "analiz-anahtari"})
    assert refused_as(refused, 403, "NOT_A_MEMBER")["details"] == {}
    assert owners.json()["id"] not in refused.text
    own_video = ready_video(world.stranger, make_clip)
    own = world.stranger.post(f"/v1/videos/{own_video['id']}/analyses", headers={"Idempotency-Key": "analiz-anahtari"})
    assert own.status_code == 202 and own.json()["id"] != owners.json()["id"]
    assert own.json()["videoId"] == own_video["id"]


def test_an_unknown_role_is_a_programming_error(world):
    from app.accounts import Caller
    with closing(sqlite3.connect(world.storage / "volleystat.sqlite3")) as db:
        db.row_factory = sqlite3.Row
        match = db.execute("SELECT * FROM matches WHERE id=?", (world.ids["match_id"],)).fetchone()
    owner = Caller(user_id_of(world.storage), "OPEN", "x", None)
    assert access.allowed(owner, match, READ) and access.allowed(owner, match, REVIEW)
    with pytest.raises(ValueError):
        access.allowed(owner, match, "CAPTAIN")
    assert not access.allowed(Caller(user_id_of(world.storage, STRANGER), "OPEN", "x", None), match, READ)
