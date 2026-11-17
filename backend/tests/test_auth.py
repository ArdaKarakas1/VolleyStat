"""Accounts: registration, login, sessions and the /v1/me routes (docs/API_V1.md, section 10; ADR-0119).

Every username, display name and password below is made up by the tests.
"""
import asyncio
import hashlib
import json
import sqlite3
import threading
from datetime import datetime, timedelta, timezone

import pytest
from fastapi.testclient import TestClient

from app import accounts
from app.main import create_app

PASSWORD = "uzun-ve-tuhaf-bir-sifre"
OTHER_PASSWORD = "baska-uzun-bir-sifre-42"


@pytest.fixture(autouse=True)
def cheap_hashing(monkeypatch):
    # The real cost (2**15) takes about 170 ms a hash; test_the_stored_hash_names_the_production_cost checks it.
    monkeypatch.setattr(accounts, "SCRYPT_N", 2 ** 10)


@pytest.fixture
def clock(monkeypatch):
    """A clock the test moves by hand. It starts at a fixed moment."""
    state = {"now": datetime(2026, 11, 17, 12, 0, 0, tzinfo=timezone.utc)}
    monkeypatch.setattr(accounts, "now", lambda: state["now"])

    class Clock:
        def advance(self, **delta):
            state["now"] += timedelta(**delta)

        @property
        def now(self):
            return state["now"]

    return Clock()


@pytest.fixture
def open_signup(monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_SIGNUP", "open")


@pytest.fixture
def api(storage, open_signup):
    with TestClient(create_app(storage, analyzer="none")) as client:
        yield client


def register(client, username="ayse.k", display_name="Ayşe K.", password=PASSWORD, **extra):
    return client.post("/v1/auth/register", json={"username": username, "displayName": display_name, "password": password, **extra})


def login(client, username="ayse.k", password=PASSWORD, **extra):
    return client.post("/v1/auth/login", json={"username": username, "password": password, **extra})


def bearer(token):
    return {"Authorization": f"Bearer {token}"}


def token_of(response):
    assert response.status_code in (200, 201), response.text
    return response.json()["session"]["token"]


def rows(storage, query, *parameters):
    conn = sqlite3.connect(storage / "volleystat.sqlite3")
    try:
        return conn.execute(query, parameters).fetchall()
    finally:
        conn.close()


def error(response):
    return response.json()["error"]


# --- registration -----------------------------------------------------------------------------

def test_a_registration_answers_the_user_a_session_and_a_device_token(api, storage):
    response = register(api)
    assert response.status_code == 201, response.text
    body = response.json()
    assert set(body) == {"user", "session", "deviceToken"}
    assert body["user"]["username"] == "ayse.k" and body["user"]["displayName"] == "Ayşe K."
    assert set(body["user"]) == {"id", "username", "displayName", "createdAt", "passwordTemporary"}
    assert body["user"]["passwordTemporary"] is False
    assert set(body["session"]) == {"token", "expiresAt"}
    me = api.get("/v1/me", headers=bearer(body["session"]["token"]))
    assert me.status_code == 200
    assert me.json() == {"user": body["user"], "teams": []}
    # The server keeps only digests of the session and device tokens and of the password.
    stored = rows(storage, "SELECT token_hash FROM sessions")[0][0]
    assert stored == hashlib.sha256(body["session"]["token"].encode()).hexdigest()
    assert rows(storage, "SELECT token_hash FROM login_devices")[0][0] == hashlib.sha256(body["deviceToken"].encode()).hexdigest()
    database = b"".join(path.read_bytes() for path in storage.glob("volleystat.sqlite3*"))
    for secret in (PASSWORD, body["session"]["token"], body["deviceToken"]):
        assert secret.encode() not in database
    assert rows(storage, "SELECT signup_via FROM users") == [("OPEN",)]


def test_the_stored_hash_names_the_production_cost(storage, open_signup, monkeypatch):
    monkeypatch.setattr(accounts, "SCRYPT_N", 2 ** 15)
    with TestClient(create_app(storage, analyzer="none")) as client:
        assert register(client).status_code == 201
        assert login(client).status_code == 200
    stored = rows(storage, "SELECT password_hash FROM users")[0][0]
    assert stored.startswith("scrypt$32768$8$3$")


def test_an_older_cost_in_a_stored_hash_still_verifies(api, monkeypatch):
    assert register(api).status_code == 201
    # The stored hash carries its own cost, so a later change of the cost locks nobody out.
    monkeypatch.setattr(accounts, "SCRYPT_N", 2 ** 11)
    assert login(api).status_code == 200


@pytest.mark.parametrize("username", ["ab", "a" * 31, "1ayse", ".ayse", "Ayse", "ayşe", "ayse k", "ayse-k", ""])
def test_a_username_outside_the_rule_is_refused(api, username):
    response = register(api, username=username)
    assert response.status_code == 422
    assert error(response)["code"] == "USERNAME_INVALID"


@pytest.mark.parametrize("username", ["abc", "a" * 30, "ayse.k_99", "z.9"])
def test_a_username_inside_the_rule_is_taken(api, username):
    assert register(api, username=username).status_code == 201


@pytest.mark.parametrize("display_name", ["A", "x" * 41, "   ", "Ay​şe", "Ay‮şe", "Ayşe\n", "Ayşe 🏐", "Ay<se>"])
def test_a_display_name_outside_the_rule_is_refused(api, display_name):
    response = register(api, display_name=display_name)
    assert response.status_code == 422
    assert error(response)["code"] == "DISPLAY_NAME_INVALID"


def test_a_display_name_is_trimmed_and_its_inner_spaces_are_collapsed(api):
    response = register(api, display_name="  Çağrı   Öz-Şen  ")
    assert response.json()["user"]["displayName"] == "Çağrı Öz-Şen"
    # Decomposed Turkish letters become the composed ones (NFC).
    other = register(api, username="mert.b", display_name="Mert Gül")
    assert other.json()["user"]["displayName"] == "Mert Gül"


@pytest.mark.parametrize("password, reason", [
    ("kisa-sifre", None),  # exactly 10 characters: allowed
    ("dokuz-har", "TOO_SHORT"),
    ("x" * 129, "TOO_LONG"),
    ("benim-adim-ayse.k-oyun", "CONTAINS_USERNAME"),
    ("BENIM-ADIM-AYSE.K-OYUN", "CONTAINS_USERNAME"),
    ("1234567890", "COMMON"),
    ("Qwertyuiop", "COMMON"),
])
def test_the_password_rule(api, password, reason):
    response = register(api, password=password)
    if reason is None:
        assert response.status_code == 201, response.text
    else:
        assert response.status_code == 422
        assert (error(response)["code"], error(response)["details"]["reason"]) == ("PASSWORD_REJECTED", reason)


def test_a_password_is_counted_and_compared_after_nfkc(api):
    # Ten full-width digits are "1234567890" after NFKC: a common password.
    response = register(api, password="１２３４５６７８９０")
    assert (error(response)["code"], error(response)["details"]["reason"]) == ("PASSWORD_REJECTED", "COMMON")
    # A password written with full-width letters logs in with the same letters written plainly.
    assert register(api, username="mert.b", password="ｖｏｌｅｙｂｏｌ-ｓｅｖｄａｍ").status_code == 201
    assert login(api, username="mert.b", password="voleybol-sevdam").status_code == 200


def test_a_taken_username_is_refused_and_the_first_account_stays(api, storage):
    assert register(api).status_code == 201
    again = register(api, display_name="Başka Biri", password=OTHER_PASSWORD)
    assert again.status_code == 409
    assert error(again)["code"] == "USERNAME_TAKEN"
    assert rows(storage, "SELECT display_name FROM users") == [("Ayşe K.",)]


def test_an_unknown_field_is_refused(api):
    response = register(api, email="ayse@example.org")
    assert response.status_code == 422
    assert error(response)["code"] == "VALIDATION_ERROR"


# --- the sign-up gate ---------------------------------------------------------------------------

@pytest.fixture
def gated(storage, monkeypatch):
    monkeypatch.delenv("VOLLEYSTAT_SIGNUP", raising=False)
    with TestClient(create_app(storage, analyzer="none")) as client:
        yield client


def test_the_gate_is_closed_by_default_and_health_says_so(gated):
    response = register(gated)
    assert response.status_code == 403
    assert error(response)["code"] == "SIGNUP_CLOSED"
    assert gated.get("/v1/health").json()["accounts"] == {"signup": "LINK", "teams": "OPERATOR"}


def test_health_names_an_open_gate(api):
    assert api.get("/v1/health").json()["accounts"]["signup"] == "OPEN"


def test_an_unknown_gate_setting_stops_the_start(storage, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_SIGNUP", "herkes")
    with pytest.raises(ValueError, match="VOLLEYSTAT_SIGNUP"):
        create_app(storage, analyzer="none")


def test_a_closed_gate_answers_a_taken_and_a_free_username_alike(gated, storage):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    assert register(gated, operatorCode=code).status_code == 201
    taken, free = register(gated), register(gated, username="hic.yok")
    assert (taken.status_code, taken.content) == (free.status_code, free.content)


def test_the_gate_comes_before_the_fields(gated):
    # Nothing about the username, the display name or the password is checked before the gate.
    response = register(gated, username="X", display_name="", password="kisa")
    assert error(response)["code"] == "SIGNUP_CLOSED"
    wrong_code = register(gated, username="X", display_name="", password="kisa", operatorCode="ABCD-EFGH-JKMN-PQRS")
    assert error(wrong_code)["code"] == "SIGNUP_CODE_INVALID" and wrong_code.status_code == 403


def test_an_operator_code_opens_one_registration(gated, storage):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    assert len(code) == 19 and code.count("-") == 3
    first = register(gated, operatorCode=code)
    assert first.status_code == 201, first.text
    second = register(gated, username="mert.b", operatorCode=code)
    assert error(second)["code"] == "SIGNUP_CODE_INVALID"
    assert rows(storage, "SELECT signup_via FROM users") == [("SIGNUP_CODE",)]


def test_an_operator_code_is_read_without_case_spaces_or_dashes(gated, storage):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    typed = " ".join(code.replace("-", "").lower()[i:i + 2] for i in range(0, 16, 2))
    assert register(gated, operatorCode=typed).status_code == 201


def test_a_refused_registration_does_not_use_up_the_code(gated, storage):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    assert register(gated, username="mert.b", operatorCode=accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")).status_code == 201
    for refused in (register(gated, username="mert.b", operatorCode=code), register(gated, password="1234567890", operatorCode=code)):
        assert refused.status_code in (409, 422)
    assert register(gated, operatorCode=code).status_code == 201


def test_an_expired_operator_code_is_refused(gated, storage, clock):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    clock.advance(days=7, seconds=1)
    assert error(register(gated, operatorCode=code))["code"] == "SIGNUP_CODE_INVALID"


def test_the_operator_code_is_stored_as_a_digest(gated, storage):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    plain = code.replace("-", "")
    assert rows(storage, "SELECT code_hash, kind FROM operator_codes") == [(hashlib.sha256(plain.encode()).hexdigest(), "SIGNUP")]


def test_a_link_finds_no_invite_before_invites_exist(gated):
    for link in ({"token": "x" * 22}, {"code": "K7QF-2M9X"}):
        response = register(gated, link=link)
        assert (response.status_code, error(response)["code"]) == (404, "LINK_NOT_FOUND")
    both = register(gated, link={"token": "x" * 22, "code": "K7QF-2M9X"})
    assert both.status_code == 422


# --- login --------------------------------------------------------------------------------------

def test_login_opens_a_new_session_and_keeps_the_device_token(api):
    first = register(api).json()
    again = login(api, deviceToken=first["deviceToken"])
    assert again.status_code == 200
    body = again.json()
    assert body["user"] == first["user"]
    assert body["session"]["token"] != first["session"]["token"]
    assert body["deviceToken"] == first["deviceToken"]
    # Both sessions work until one is closed.
    for token in (first["session"]["token"], body["session"]["token"]):
        assert api.get("/v1/me", headers=bearer(token)).status_code == 200


def test_a_device_token_of_another_account_is_not_kept(api):
    other = register(api, username="mert.b").json()
    register(api)
    body = login(api, deviceToken=other["deviceToken"]).json()
    assert body["deviceToken"] != other["deviceToken"]


def test_a_wrong_password_and_an_unknown_username_get_the_same_answer(api):
    register(api)
    wrong, unknown = login(api, password=OTHER_PASSWORD), login(api, username="hic.yok")
    assert wrong.status_code == unknown.status_code == 401
    assert wrong.content == unknown.content
    assert error(wrong)["code"] == "LOGIN_FAILED"
    assert wrong.headers["www-authenticate"] == "Bearer"


def test_an_unknown_username_still_costs_one_hash(api, monkeypatch):
    register(api)
    calls = []
    original = accounts.verify_password
    monkeypatch.setattr(accounts, "verify_password", lambda password, stored: calls.append(stored) or original(password, stored))
    login(api, username="hic.yok")
    assert len(calls) == 1 and calls[0].startswith("scrypt$")


def test_login_compares_the_username_exactly(api):
    register(api)
    assert login(api, username="AYSE.K").status_code == 401


# --- throttling ---------------------------------------------------------------------------------

def test_five_failures_are_free_and_the_sixth_attempt_waits(api, clock):
    register(api)
    for _ in range(5):
        assert login(api, password=OTHER_PASSWORD).status_code == 401
    locked = login(api)
    assert locked.status_code == 429
    assert error(locked)["code"] == "TOO_MANY_ATTEMPTS"
    assert error(locked)["details"] == {"retryAfterSeconds": 30, "scope": "USER"}
    assert locked.headers["retry-after"] == "30"
    clock.advance(seconds=30)
    # One more failure doubles the wait.
    assert login(api, password=OTHER_PASSWORD).status_code == 401
    assert error(login(api))["details"]["retryAfterSeconds"] == 60
    clock.advance(seconds=60)
    assert login(api).status_code == 200
    # A success clears the count.
    for _ in range(5):
        assert login(api, password=OTHER_PASSWORD).status_code == 401
    assert login(api).status_code == 429


def test_the_wait_stops_growing_at_fifteen_minutes(api, clock):
    register(api)
    waits = []
    for _ in range(12):
        response = login(api, password=OTHER_PASSWORD)
        if response.status_code == 429:
            waits.append(error(response)["details"]["retryAfterSeconds"])
            clock.advance(seconds=waits[-1])
            assert login(api, password=OTHER_PASSWORD).status_code == 401
    assert max(waits) <= 900
    assert error(login(api))["details"]["retryAfterSeconds"] == 900


def test_failures_are_forgotten_an_hour_after_the_last_one(api, clock):
    register(api)
    for _ in range(5):
        login(api, password=OTHER_PASSWORD)
    clock.advance(hours=1)
    assert login(api, password=OTHER_PASSWORD).status_code == 401
    assert login(api).status_code == 200


def test_a_known_phone_logs_in_while_strangers_lock_the_username(api, clock):
    device = register(api).json()["deviceToken"]
    for _ in range(5):
        login(api, password=OTHER_PASSWORD)
    assert login(api).status_code == 429
    assert login(api, deviceToken=device).status_code == 200
    # The known phone counts its own failures under its own key.
    for _ in range(5):
        assert login(api, password=OTHER_PASSWORD, deviceToken=device).status_code == 401
    locked = login(api, deviceToken=device)
    assert error(locked)["details"]["scope"] == "DEVICE"


def test_a_locked_attempt_runs_no_hash(api, monkeypatch):
    register(api)
    for _ in range(5):
        login(api, password=OTHER_PASSWORD)
    monkeypatch.setattr(accounts, "verify_password", lambda *_: pytest.fail("a locked login must not hash"))
    assert login(api).status_code == 429


def test_fifty_failures_in_an_hour_lock_the_username_for_every_source():
    limiter = accounts.Limiter()
    start = 1_000_000.0
    for index in range(50):
        source = f"10.0.{index}.1"
        assert limiter.wait(accounts.login_keys("ayse.k", source), start + index) is None
        limiter.fail(accounts.login_keys("ayse.k", source), start + index)
    seconds, scope = limiter.wait(accounts.login_keys("ayse.k", "10.9.9.9"), start + 50)
    assert scope == "USER" and 895 <= seconds <= 900
    # Another username is not touched.
    assert limiter.wait(accounts.login_keys("mert.b", "10.9.9.9"), start + 50) is None


def test_the_limiter_never_forgets_a_locked_key_to_make_room():
    limiter = accounts.Limiter(max_keys=3)
    locked = accounts.login_keys("ayse.k", "local")
    for second in range(5):
        limiter.fail(locked, 100.0 + second)
    for name in ("b", "c", "d", "e"):
        limiter.fail(accounts.login_keys(f"user.{name}", "local"), 105.0)
    assert limiter.wait(locked, 106.0) is not None
    assert len(limiter) <= 3


# --- sessions -----------------------------------------------------------------------------------

def test_a_request_without_a_valid_session_is_refused(api):
    for headers in ({}, bearer("yok"), {"Authorization": "Basic eA=="}):
        response = api.get("/v1/me", headers=headers)
        assert response.status_code == 401
        assert error(response)["code"] == "AUTH_REQUIRED"
        assert response.headers["www-authenticate"] == "Bearer"


def test_a_session_ends_after_thirty_idle_days(api, clock):
    token = token_of(register(api))
    clock.advance(days=29, hours=23)
    assert api.get("/v1/me", headers=bearer(token)).status_code == 200
    clock.advance(days=29, hours=23)
    assert api.get("/v1/me", headers=bearer(token)).status_code == 200
    clock.advance(days=30, seconds=1)
    assert api.get("/v1/me", headers=bearer(token)).status_code == 401


def test_a_session_ends_after_180_days_however_often_it_is_used(api, clock):
    token = token_of(register(api))
    for _ in range(17):
        clock.advance(days=10)
        assert api.get("/v1/me", headers=bearer(token)).status_code == 200
    clock.advance(days=10, seconds=1)
    assert api.get("/v1/me", headers=bearer(token)).status_code == 401


def test_a_session_records_its_use_at_most_once_an_hour(api, storage, clock):
    token = token_of(register(api))
    written = rows(storage, "SELECT last_used_at FROM sessions")[0][0]
    clock.advance(minutes=59)
    api.get("/v1/me", headers=bearer(token))
    assert rows(storage, "SELECT last_used_at FROM sessions")[0][0] == written
    clock.advance(minutes=2)
    api.get("/v1/me", headers=bearer(token))
    assert rows(storage, "SELECT last_used_at FROM sessions")[0][0] != written


def test_logout_closes_only_that_session(api):
    first = token_of(register(api))
    second = token_of(login(api))
    assert api.post("/v1/auth/logout", headers=bearer(first), json={}).status_code == 204
    assert api.get("/v1/me", headers=bearer(first)).status_code == 401
    assert api.get("/v1/me", headers=bearer(second)).status_code == 200
    assert api.post("/v1/auth/logout", headers=bearer(first), json={}).status_code == 401


def test_the_routes_of_matches_still_answer_without_a_session(api):
    # Enforcement comes with its own slice (B3). Until then the app of version 0.7 keeps working.
    created = api.post("/v1/matches", json={"homeTeamName": "A Takımı", "awayTeamName": "B Takımı"})
    assert created.status_code == 201


# --- the /v1/me routes --------------------------------------------------------------------------

def test_the_display_name_can_change(api):
    token = token_of(register(api))
    changed = api.put("/v1/me/display-name", headers=bearer(token), json={"displayName": "  Ayşe   Kaya "})
    assert changed.status_code == 200
    assert changed.json()["user"]["displayName"] == "Ayşe Kaya"
    refused = api.put("/v1/me/display-name", headers=bearer(token), json={"displayName": "A"})
    assert error(refused)["code"] == "DISPLAY_NAME_INVALID"


def test_a_password_change_closes_the_other_sessions_and_forgets_the_phones(api, storage):
    first = register(api).json()
    other = token_of(login(api))
    wrong = api.put("/v1/me/password", headers=bearer(first["session"]["token"]),
                    json={"currentPassword": OTHER_PASSWORD, "newPassword": "yepyeni-bir-sifre-7"})
    assert (wrong.status_code, error(wrong)["code"]) == (403, "PASSWORD_WRONG")
    weak = api.put("/v1/me/password", headers=bearer(first["session"]["token"]),
                   json={"currentPassword": PASSWORD, "newPassword": "1234567890"})
    assert error(weak)["code"] == "PASSWORD_REJECTED"
    changed = api.put("/v1/me/password", headers=bearer(first["session"]["token"]),
                      json={"currentPassword": PASSWORD, "newPassword": "yepyeni-bir-sifre-7"})
    assert changed.status_code == 200
    assert api.get("/v1/me", headers=bearer(first["session"]["token"])).status_code == 200
    assert api.get("/v1/me", headers=bearer(other)).status_code == 401
    assert rows(storage, "SELECT COUNT(*) FROM login_devices") == [(0,)]
    assert login(api).status_code == 401
    assert login(api, password="yepyeni-bir-sifre-7").status_code == 200


def test_a_wrong_current_password_counts_as_a_failure(api):
    token = token_of(register(api))
    for _ in range(5):
        api.put("/v1/me/password", headers=bearer(token), json={"currentPassword": OTHER_PASSWORD, "newPassword": "yepyeni-bir-sifre-7"})
    locked = api.put("/v1/me/password", headers=bearer(token), json={"currentPassword": PASSWORD, "newPassword": "yepyeni-bir-sifre-7"})
    assert locked.status_code == 429


def test_deleting_the_account_removes_every_trace(api, storage):
    body = register(api, username="silinecek.kisi", display_name="Silinecek Kişi").json()
    token = body["session"]["token"]
    wrong = api.post("/v1/me/delete", headers=bearer(token), json={"password": OTHER_PASSWORD})
    assert (wrong.status_code, error(wrong)["code"]) == (403, "PASSWORD_WRONG")
    assert api.post("/v1/me/delete", headers=bearer(token), json={"password": PASSWORD}).status_code == 204
    assert api.get("/v1/me", headers=bearer(token)).status_code == 401
    for table in ("users", "sessions", "login_devices"):
        assert rows(storage, f"SELECT COUNT(*) FROM {table}") == [(0,)]
    files = b"".join(path.read_bytes() for path in storage.glob("volleystat.sqlite3*"))
    for trace in ("silinecek.kisi", "Silinecek Kişi", body["user"]["id"]):
        assert trace.encode() not in files, trace
    assert login(api, username="silinecek.kisi").status_code == 401


# --- the hash pool ------------------------------------------------------------------------------

def test_hashing_waits_in_its_own_pool_and_a_crowd_gets_server_busy(storage, open_signup, monkeypatch):
    release = threading.Event()
    started = threading.Semaphore(0)

    def slow_hash(password, salt, n):
        started.release()
        release.wait(10)
        return b"\0" * 32

    monkeypatch.setattr(accounts, "_scrypt", slow_hash)
    app = create_app(storage, analyzer="none")

    async def crowd():
        import httpx
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            logins = [asyncio.create_task(client.post("/v1/auth/login", json={"username": f"user.{i}", "password": PASSWORD}))
                      for i in range(18)]
            await asyncio.sleep(0.3)
            # Two hashes run and sixteen wait: one more is refused at once.
            busy = await client.post("/v1/auth/login", json={"username": "user.x", "password": PASSWORD})
            # The rest of the API is not held up by the hashes.
            health = await asyncio.wait_for(client.get("/v1/health"), 1)
            release.set()
            done = await asyncio.gather(*logins)
            return busy, health, done

    busy, health, done = asyncio.run(crowd())
    assert busy.status_code == 503
    assert busy.json()["error"]["code"] == "SERVER_BUSY"
    assert busy.headers["retry-after"] == "1"
    assert health.status_code == 200
    assert sorted({response.status_code for response in done}) == [401]


# --- the admin commands -------------------------------------------------------------------------

def test_reset_password_prints_a_temporary_password_and_closes_every_session(api, storage, capsys):
    from app import admin
    first = register(api).json()
    assert admin.main(["--storage", str(storage), "reset-password", "ayse.k"]) == 0
    temporary = capsys.readouterr().out.strip().splitlines()[-1]
    assert api.get("/v1/me", headers=bearer(first["session"]["token"])).status_code == 401
    assert login(api).status_code == 401
    after = login(api, password=temporary)
    assert after.status_code == 200
    assert after.json()["user"]["passwordTemporary"] is True
    assert rows(storage, "SELECT COUNT(*) FROM login_devices") == [(1,)]
    token = after.json()["session"]["token"]
    changed = api.put("/v1/me/password", headers=bearer(token), json={"currentPassword": temporary, "newPassword": "yepyeni-bir-sifre-7"})
    assert changed.json()["user"]["passwordTemporary"] is False


def test_reset_password_names_an_unknown_user(storage, capsys):
    from app import admin
    assert admin.main(["--storage", str(storage), "reset-password", "hic.yok"]) == 1
    assert "hic.yok" in capsys.readouterr().err


def test_signup_code_prints_a_code_that_opens_a_registration(gated, storage, capsys):
    from app import admin
    assert admin.main(["--storage", str(storage), "signup-code"]) == 0
    code = capsys.readouterr().out.strip().splitlines()[-1]
    assert register(gated, operatorCode=code).status_code == 201


def test_the_server_runs_without_an_access_log_and_ignores_forwarded_addresses(monkeypatch):
    from app import __main__ as entry
    seen = {}
    monkeypatch.setattr(entry.uvicorn, "run", lambda app, **options: seen.update(options))
    monkeypatch.setattr("sys.argv", ["app", "--analyzer", "none"])
    monkeypatch.setenv("VOLLEYSTAT_STORAGE", str(__import__("tempfile").mkdtemp()))
    entry.main()
    assert (seen["host"], seen["access_log"], seen["forwarded_allow_ips"]) == ("127.0.0.1", False, "")
