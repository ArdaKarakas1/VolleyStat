"""Accounts: registration, login, sessions and the /v1/me routes (docs/API_V1.md, section 10; ADR-0119).

Every username, display name and password below is made up by the tests.
"""
import asyncio
import hashlib
import json
import sqlite3
import threading
import time
from datetime import datetime, timedelta, timezone

import httpx
import pytest
from fastapi.testclient import TestClient

from app import accounts
from app.main import create_app

PASSWORD = "uzun-ve-tuhaf-bir-sifre"
# Read when the module loads, before the fixture below lowers the cost for speed.
PRODUCTION_N = accounts.SCRYPT_N
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


def test_the_production_cost_is_two_to_the_fifteenth():
    assert PRODUCTION_N == 2 ** 15


def test_the_stored_hash_names_the_production_cost(storage, open_signup, monkeypatch):
    monkeypatch.setattr(accounts, "SCRYPT_N", PRODUCTION_N)
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
    # Long common passwords: the list holds those of 10 characters and more.
    ("password123", "COMMON"),
    ("galatasaray", "COMMON"),
    ("Fenerbahce", "COMMON"),
    ("0000000000", "REPETITIVE"),
    ("ababababab", "REPETITIVE"),
    ("1212121212", "REPETITIVE"),
    ("abcabcabcab", "REPETITIVE"),
    ("abcdabcdab", None),
    # Turkish letters lose their marks when the password is compared: ş counts as s.
    ("ayşe.k-nin-şifresi", "CONTAINS_USERNAME"),
    ("x" * 120 + "-sifre-7", None),
])
def test_the_password_rule(api, password, reason):
    response = register(api, password=password)
    if reason is None:
        assert response.status_code == 201, response.text
    else:
        assert response.status_code == 422
        assert (error(response)["code"], error(response)["details"]["reason"]) == ("PASSWORD_REJECTED", reason)


@pytest.mark.parametrize("password", ["İLKER.K-2026-sifre", "ılker.k-2026-sifre", "Ilker.K-2026-sifre"])
def test_the_turkish_i_letters_count_as_i_when_a_password_is_compared(api, password):
    # Casefolding İ leaves an i with a combining dot, and ı is a letter of its own: both count as i.
    response = register(api, username="ilker.k", password=password)
    assert (error(response)["code"], error(response)["details"]["reason"]) == ("PASSWORD_REJECTED", "CONTAINS_USERNAME")


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
    # The owner's phone stays known, so the owner logs in with it whatever strangers try.
    after = login(api, password=temporary, deviceToken=first["deviceToken"])
    assert after.status_code == 200
    assert after.json()["deviceToken"] == first["deviceToken"]
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


# --- review of the slice: races, limits and edges --------------------------------------------------

def concurrently(*calls):
    """Run each call in its own thread and return their results in order."""
    results = [None] * len(calls)

    def run(index, call):
        results[index] = call()

    threads = [threading.Thread(target=run, args=(index, call)) for index, call in enumerate(calls)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(20)
    return results


def test_guesses_sent_together_get_five_hashes_and_then_the_lock(storage, open_signup, monkeypatch):
    hashed = []
    release = threading.Event()
    original = accounts._scrypt

    def slow(password, salt, n):
        hashed.append(1)
        release.wait(5)
        return original(password, salt, n)

    with TestClient(create_app(storage, analyzer="none")) as client:
        register(client)
        monkeypatch.setattr(accounts, "_scrypt", slow)

        async def burst():
            transport = httpx.ASGITransport(app=client.app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as other:
                guesses = [asyncio.create_task(other.post("/v1/auth/login", json={"username": "ayse.k", "password": OTHER_PASSWORD}))
                           for _ in range(18)]
                await asyncio.sleep(0.3)
                release.set()
                return [response.status_code for response in await asyncio.gather(*guesses)]

        codes = asyncio.run(burst())
    assert sorted(codes) == [401] * 5 + [429] * 13
    assert len(hashed) == 5


def test_a_stranger_who_locks_the_username_can_not_block_the_owners_own_password_checks(api):
    token = token_of(register(api))
    for _ in range(6):
        login(api, password=OTHER_PASSWORD)
    assert login(api).status_code == 429
    changed = api.put("/v1/me/password", headers=bearer(token), json={"currentPassword": PASSWORD, "newPassword": "yepyeni-bir-sifre-7"})
    assert changed.status_code == 200
    assert api.post("/v1/me/delete", headers=bearer(token), json={"password": "yepyeni-bir-sifre-7"}).status_code == 204


def test_wrong_passwords_inside_a_session_lock_the_account_check_and_not_the_login(api):
    token = token_of(register(api))
    for _ in range(5):
        wrong = api.post("/v1/me/delete", headers=bearer(token), json={"password": OTHER_PASSWORD})
        assert wrong.status_code == 403
    locked = api.post("/v1/me/delete", headers=bearer(token), json={"password": PASSWORD})
    assert (locked.status_code, error(locked)["details"]["scope"]) == (429, "ACCOUNT")
    assert login(api).status_code == 200


def hold_hash(monkeypatch, name, then):
    """Run `then` once inside the first call of accounts.<name>, after the real hash."""
    original = getattr(accounts, name)
    done = []

    def held(*arguments):
        result = original(*arguments)
        if not done:
            done.append(True)
            then()
        return result

    monkeypatch.setattr(accounts, name, held)


def test_a_password_change_whose_session_ended_while_it_hashed_writes_nothing(storage, open_signup, monkeypatch):
    with TestClient(create_app(storage, analyzer="none")) as client:
        first = register(client).json()
        thief = token_of(login(client))
        db = storage / "volleystat.sqlite3"
        # The owner changes the password (which ends the thief's session) while the thief's change hashes.
        hold_hash(monkeypatch, "hash_password", lambda: accounts.reset_password(db, "ayse.k"))
        late = client.put("/v1/me/password", headers=bearer(thief), json={"currentPassword": PASSWORD, "newPassword": "hirsizin-sifresi-7"})
        assert late.status_code == 401
        assert login(client, password="hirsizin-sifresi-7").status_code == 401
        assert rows(storage, "SELECT password_temporary_until IS NOT NULL FROM users") == [(1,)]
        assert api_me(client, first["session"]["token"]) == 401


def api_me(client, token):
    return client.get("/v1/me", headers=bearer(token)).status_code


def test_two_password_changes_of_one_session_change_it_once(storage, open_signup, monkeypatch):
    with TestClient(create_app(storage, analyzer="none")) as client:
        token = token_of(register(client))
        barrier = threading.Barrier(2)
        original = accounts.hash_password

        def together(password):
            barrier.wait(5)
            return original(password)

        monkeypatch.setattr(accounts, "hash_password", together)
        codes = concurrently(
            lambda: client.put("/v1/me/password", headers=bearer(token), json={"currentPassword": PASSWORD, "newPassword": "birinci-yeni-sifre"}).status_code,
            lambda: client.put("/v1/me/password", headers=bearer(token), json={"currentPassword": PASSWORD, "newPassword": "ikinci-yeni-sifre"}).status_code)
        assert sorted(codes) == [200, 403]
        assert sorted(login(client, password=word).status_code for word in ("birinci-yeni-sifre", "ikinci-yeni-sifre")) == [200, 401]


def test_a_deletion_whose_password_changed_while_it_hashed_deletes_nothing(storage, open_signup, monkeypatch):
    with TestClient(create_app(storage, analyzer="none")) as client:
        token = token_of(register(client))
        db = storage / "volleystat.sqlite3"

        def change_password_in_between():
            conn = sqlite3.connect(db)
            conn.execute("UPDATE users SET password_hash=?", (accounts.hash_password("baska-bir-sifre-9"),))
            conn.commit()
            conn.close()

        hold_hash(monkeypatch, "verify_password", change_password_in_between)
        late = client.post("/v1/me/delete", headers=bearer(token), json={"password": PASSWORD})
        assert late.status_code == 403
        assert rows(storage, "SELECT COUNT(*) FROM users") == [(1,)]


def test_a_display_name_change_of_a_deleted_account_answers_401(storage, open_signup, monkeypatch):
    with TestClient(create_app(storage, analyzer="none")) as client:
        token = token_of(register(client))
        original = accounts.clean_display_name

        def delete_first(text):
            conn = sqlite3.connect(storage / "volleystat.sqlite3")
            conn.execute("PRAGMA foreign_keys=ON")
            conn.execute("DELETE FROM users")
            conn.commit()
            conn.close()
            return original(text)

        monkeypatch.setattr(accounts, "clean_display_name", delete_first)
        response = client.put("/v1/me/display-name", headers=bearer(token), json={"displayName": "Yeni Ad"})
        assert (response.status_code, response.headers["www-authenticate"]) == (401, "Bearer")


def test_a_login_racing_a_password_change_opens_no_session(storage, open_signup, monkeypatch):
    with TestClient(create_app(storage, analyzer="none")) as client:
        register(client)

        def change():
            conn = sqlite3.connect(storage / "volleystat.sqlite3")
            conn.execute("UPDATE users SET password_hash=?", (accounts.hash_password("yepyeni-bir-sifre-7"),))
            conn.commit()
            conn.close()

        hold_hash(monkeypatch, "verify_password", change)
        response = login(client)
        assert (response.status_code, response.headers["www-authenticate"]) == (401, "Bearer")


def test_two_registrations_of_one_username_at_once_give_201_and_409(storage, open_signup, monkeypatch):
    with TestClient(create_app(storage, analyzer="none"), raise_server_exceptions=False) as client:
        barrier = threading.Barrier(2)
        original = accounts.hash_password
        monkeypatch.setattr(accounts, "hash_password", lambda password: barrier.wait(5) is None or original(password))
        codes = concurrently(lambda: register(client).status_code, lambda: register(client).status_code)
        assert sorted(codes) == [201, 409]


def test_two_registrations_with_one_code_at_once_open_one_account(gated, storage, monkeypatch):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    barrier = threading.Barrier(2)
    original = accounts.hash_password
    monkeypatch.setattr(accounts, "hash_password", lambda password: barrier.wait(5) is None or original(password))
    codes = concurrently(lambda: register(gated, operatorCode=code).status_code,
                         lambda: register(gated, username="mert.b", operatorCode=code).status_code)
    assert sorted(codes) == [201, 403]
    assert rows(storage, "SELECT COUNT(*) FROM users") == [(1,)]


def test_a_wrong_or_expired_code_answers_the_gate_even_for_a_taken_username(gated, storage, clock):
    first = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    assert register(gated, operatorCode=first).status_code == 201
    stale = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    clock.advance(days=7, seconds=1)
    accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")  # a live code exists too
    for code in (stale, "ABCD-EFGH-JKMN-PQRS"):
        response = register(gated, operatorCode=code)
        assert (response.status_code, error(response)["code"]) == (403, "SIGNUP_CODE_INVALID")


def test_an_operator_code_still_opens_a_registration_on_its_seventh_day(gated, storage, clock):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP")
    clock.advance(days=6, hours=23)
    assert register(gated, operatorCode=code).status_code == 201


def test_the_temporary_password_ends_after_seven_days(api, storage, clock):
    register(api)
    temporary = accounts.reset_password(storage / "volleystat.sqlite3", "ayse.k")
    clock.advance(days=6, hours=23)
    assert login(api, password=temporary).status_code == 200
    clock.advance(hours=1, seconds=1)
    response = login(api, password=temporary)
    assert (response.status_code, error(response)["code"]) == (401, "LOGIN_FAILED")


def test_a_digest_of_an_older_cost_is_renewed_at_the_next_login(api, storage, monkeypatch):
    register(api)
    monkeypatch.setattr(accounts, "SCRYPT_N", 2 ** 11)
    assert login(api).status_code == 200
    assert rows(storage, "SELECT password_hash FROM users")[0][0].startswith("scrypt$2048$8$3$")
    assert login(api).status_code == 200


def test_a_higher_cost_still_hashes(api, monkeypatch):
    # scrypt needs 128 * r * n bytes; the memory limit grows with the cost.
    monkeypatch.setattr(accounts, "SCRYPT_N", 2 ** 16)
    assert accounts.hash_password(PASSWORD).startswith("scrypt$65536$8$3$")


def test_an_unknown_username_is_checked_at_the_current_cost(api, monkeypatch):
    register(api)
    seen = []
    original = accounts.verify_password
    monkeypatch.setattr(accounts, "verify_password", lambda password, stored: seen.append(stored) or original(password, stored))
    login(api, username="hic.yok")
    assert seen[0].split("$")[1:4] == [str(accounts.SCRYPT_N), "8", "3"]


def test_every_digest_has_its_own_salt(api, storage):
    register(api)
    register(api, username="mert.b")
    first, second = [row[0] for row in rows(storage, "SELECT password_hash FROM users")]
    assert first.split("$")[4] != second.split("$")[4]


def test_the_pool_runs_two_hashes_at_once_and_gives_every_slot_back(storage, open_signup, monkeypatch):
    running, most = [], [0]
    lock = threading.Lock()
    original = accounts._scrypt

    def counted(password, salt, n):
        with lock:
            running.append(1)
            most[0] = max(most[0], len(running))
        try:
            time.sleep(0.05)
            return original(password, salt, n)
        finally:
            with lock:
                running.pop()

    monkeypatch.setattr(accounts, "_scrypt", counted)
    with TestClient(create_app(storage, analyzer="none")) as client:
        names = [f"oyuncu.{index}" for index in range(6)]
        for name in names:
            register(client, username=name)
        most[0] = 0
        codes = concurrently(*[lambda name=name: login(client, username=name).status_code for name in names])
        assert codes == [200] * 6
        assert most[0] == 2
        # Far more hashes than there are slots, one after the other: no slot is ever lost.
        for _ in range(25):
            assert login(client, username=names[0]).status_code == 200


def test_a_device_token_ends_180_days_after_its_last_use(api, clock):
    device = register(api).json()["deviceToken"]
    clock.advance(days=179)
    assert login(api, deviceToken=device).json()["deviceToken"] == device
    clock.advance(days=179)
    assert login(api, deviceToken=device).json()["deviceToken"] == device
    clock.advance(days=180)
    assert login(api, deviceToken=device).json()["deviceToken"] != device


def test_a_session_works_until_its_180th_day(api, clock):
    token = token_of(register(api))
    for _ in range(17):
        clock.advance(days=10)
        assert api.get("/v1/me", headers=bearer(token)).status_code == 200
    clock.advance(days=9, hours=23)
    assert api.get("/v1/me", headers=bearer(token)).status_code == 200


def test_a_new_password_may_not_contain_the_username(api):
    token = token_of(register(api))
    response = api.put("/v1/me/password", headers=bearer(token), json={"currentPassword": PASSWORD, "newPassword": "ayse.k-yeni-sifre"})
    assert error(response)["details"]["reason"] == "CONTAINS_USERNAME"


@pytest.mark.parametrize("display_name", ["Ali'nin Oğlu", "Ay", "Oyuncu 7", "Ece-Su Öz"])
def test_a_display_name_inside_the_rule_is_kept(display_name):
    assert accounts.clean_display_name(display_name) == display_name


@pytest.mark.parametrize("display_name", [". -", "''", "--"])
def test_a_display_name_without_a_letter_or_digit_is_refused(display_name):
    assert accounts.clean_display_name(display_name) is None


def test_the_longest_lock_among_the_keys_wins():
    limiter = accounts.Limiter()
    for index in range(45):
        limiter.fail(accounts.login_keys("ayse.k", f"10.0.{index}.1"), 1000.0 + index)
    keys = accounts.login_keys("ayse.k", "10.9.9.9")
    for index in range(5):
        limiter.fail(keys, 1045.0 + index)
    seconds, _scope = limiter.wait(keys, 1050.0)
    assert seconds >= 895


def test_a_flood_of_new_keys_does_not_wash_out_a_count_under_attack():
    limiter = accounts.Limiter(max_keys=10)
    victim = accounts.login_keys("ayse.k", "local")
    for second in range(4):
        limiter.fail(victim, 100.0 + second)
    for index in range(50):
        limiter.fail([(f"login:user:junk.{index}:local", accounts.BACKOFF, "USER")], 110.0 + index)
    # The fifth failure of the victim still locks it: its count of four was kept.
    limiter.fail(victim, 200.0)
    assert limiter.wait(victim, 201.0) is not None


class FakeRequest:
    def __init__(self, host):
        self.client = type("Client", (), {"host": host})() if host is not None else None


@pytest.mark.parametrize("host, source", [
    (None, "local"), ("testclient", "local"), ("127.0.0.1", "local"), ("::1", "local"),
    ("203.0.113.5", "203.0.113.5"), ("::ffff:203.0.113.5", "203.0.113.5"), ("::ffff:127.0.0.1", "local"),
    ("2001:db8:1:2:3:4:5:6", "2001:db8:1:2::/64"), ("2001:db8:1:2:aaaa::1", "2001:db8:1:2::/64"),
])
def test_the_source_of_a_request(host, source):
    assert accounts.source_of(FakeRequest(host)) == source


def test_a_deleted_account_leaves_no_trace_while_another_connection_is_open(api, storage):
    body = register(api, username="silinecek.kisi", display_name="Silinecek Kişi").json()
    other = sqlite3.connect(storage / "volleystat.sqlite3")
    try:
        other.execute("SELECT 1").fetchall()
        assert api.post("/v1/me/delete", headers=bearer(body["session"]["token"]), json={"password": PASSWORD}).status_code == 204
        files = b"".join(path.read_bytes() for path in storage.glob("volleystat.sqlite3*"))
        for trace in ("silinecek.kisi", "Silinecek Kişi", body["user"]["id"]):
            assert trace.encode() not in files, trace
    finally:
        other.close()


def test_a_lowercase_bearer_scheme_is_read(api):
    token = token_of(register(api))
    assert api.get("/v1/me", headers={"Authorization": f"bearer {token}"}).status_code == 200


# --- mutation run of the slice: what the earlier tests let through ------------------------------

def test_an_operator_code_uses_only_characters_that_can_not_be_confused(storage):
    codes = [accounts.issue_operator_code(storage / "volleystat.sqlite3", "SIGNUP") for _ in range(40)]
    letters = set("".join(codes).replace("-", ""))
    assert letters <= set(accounts.CODE_ALPHABET)
    assert not letters & set("01ILO")
    assert all(len(code) == 19 and [len(group) for group in code.split("-")] == [4, 4, 4, 4] for code in codes)


@pytest.mark.parametrize("text", ["ABC", "ABCD-EFGH-JKMN-PQR", "ABCD-EFGH-JKMN-PQRS-T", "ABCD-EFGH-JKMN-PQR0", "ABCD EFGH JKMN PQRI"])
def test_a_text_that_can_not_be_an_operator_code_is_read_as_none(text):
    assert accounts.normalize_operator_code(text) is None


def test_a_captain_code_does_not_open_a_registration_before_the_teams_slice(gated, storage):
    code = accounts.issue_operator_code(storage / "volleystat.sqlite3", "CAPTAIN")
    # The gate answers before the fields: a bad username still gets the code's answer.
    response = register(gated, username="X", operatorCode=code)
    assert (response.status_code, error(response)["code"]) == (403, "SIGNUP_CODE_INVALID")


@pytest.mark.parametrize("display_name", ["张伟", "Ǆemal Öz", "Ana Lı"])
def test_letters_of_every_script_are_letters(display_name):
    assert accounts.clean_display_name(display_name) == display_name


@pytest.mark.parametrize("display_name", ["Ay se", "Ay\nse", "Ay\tse", "Aysé́", "́́"])
def test_other_spaces_controls_and_loose_marks_are_refused(display_name):
    assert accounts.clean_display_name(display_name) is None


def test_the_length_of_a_password_is_counted_after_nfkc(api):
    # ﬀ is one character and becomes "ff" under NFKC: nine characters typed count as ten.
    assert register(api, password="ﬀsifre-77").status_code == 201


def test_a_digest_of_another_kind_is_refused():
    with pytest.raises(ValueError):
        accounts.verify_password(PASSWORD, "bcrypt$32768$8$3$AAAA$AAAA")
    with pytest.raises(ValueError):
        accounts.verify_password(PASSWORD, "scrypt$32768$4$3$AAAA$AAAA")


def test_failures_are_kept_for_an_hour_after_the_last_one(api, clock):
    register(api)
    for _ in range(5):
        login(api, password=OTHER_PASSWORD)
    clock.advance(minutes=59)
    # The count of five is still there: one more failure locks the username again at once.
    assert login(api, password=OTHER_PASSWORD).status_code == 401
    assert login(api).status_code == 429


def test_the_fifty_an_hour_window_forgets_failures_older_than_an_hour():
    limiter = accounts.Limiter()
    keys = [("login:user:ayse.k", accounts.WINDOW, "USER")]
    for second in range(49):
        limiter.fail(keys, 1000.0 + second)
    limiter.fail(keys, 1000.0 + 3600 + 1)
    assert limiter.wait(keys, 1000.0 + 3602) is None


def test_the_fifty_an_hour_window_starts_again_after_its_lock():
    limiter = accounts.Limiter()
    keys = [("login:user:ayse.k", accounts.WINDOW, "USER")]
    for second in range(50):
        limiter.fail(keys, 1000.0 + second)
    assert limiter.wait(keys, 1050.0) is not None
    limiter.fail(keys, 1049.0 + 901)
    assert limiter.wait(keys, 1049.0 + 902) is None


def test_the_limiter_holds_ten_thousand_keys():
    assert accounts.Limiter().max_keys == 10_000


def test_a_locked_window_key_is_not_forgotten_to_make_room():
    limiter = accounts.Limiter(max_keys=2)
    window = [("login:user:ayse.k", accounts.WINDOW, "USER")]
    for second in range(50):
        limiter.fail(window, 1000.0 + second)
    for name in ("b", "c", "d"):
        limiter.fail([(f"login:user:{name}:local", accounts.BACKOFF, "USER")], 1060.0)
    assert limiter.wait(window, 1061.0) is not None


def test_a_session_answer_names_the_idle_end(api, clock):
    session = register(api).json()["session"]
    assert session["expiresAt"] == accounts.iso(clock.now + timedelta(days=30))
    assert len(session["token"]) >= 43


def test_the_temporary_password_ends_exactly_seven_days_after_the_reset(api, storage, clock):
    register(api)
    temporary = accounts.reset_password(storage / "volleystat.sqlite3", "ayse.k")
    assert len(temporary) == 19 and set(temporary.replace("-", "")) <= set(accounts.CODE_ALPHABET)
    clock.advance(days=7)
    assert login(api, password=temporary).status_code == 401


def test_a_right_password_inside_a_session_clears_the_count(api):
    token = token_of(register(api))
    for _ in range(4):
        assert api.post("/v1/me/delete", headers=bearer(token), json={"password": OTHER_PASSWORD}).status_code == 403
    assert api.put("/v1/me/display-name", headers=bearer(token), json={"displayName": "Ayşe"}).status_code == 200
    changed = api.put("/v1/me/password", headers=bearer(token), json={"currentPassword": PASSWORD, "newPassword": "yepyeni-bir-sifre-7"})
    assert changed.status_code == 200
    for _ in range(4):
        assert api.post("/v1/me/delete", headers=bearer(token), json={"password": OTHER_PASSWORD}).status_code == 403


def test_a_taken_username_costs_no_hash(api, monkeypatch):
    register(api)
    monkeypatch.setattr(accounts, "hash_password", lambda password: pytest.fail("a taken username must not hash"))
    assert register(api).status_code == 409


def test_a_login_removes_the_ended_sessions_and_the_forgotten_phones_of_the_account(api, storage, clock):
    register(api)
    clock.advance(days=181)
    assert login(api).status_code == 200
    # The session and the phone of the registration ended long ago; only the new ones stay.
    assert rows(storage, "SELECT COUNT(*) FROM sessions") == [(1,)]
    assert rows(storage, "SELECT COUNT(*) FROM login_devices") == [(1,)]


@pytest.mark.parametrize("value, mode", [(" OPEN ", "OPEN"), ("Link", "LINK")])
def test_the_gate_setting_is_read_without_case_or_spaces(storage, monkeypatch, value, mode):
    monkeypatch.setenv("VOLLEYSTAT_SIGNUP", value)
    with TestClient(create_app(storage, analyzer="none")) as client:
        assert client.get("/v1/health").json()["accounts"]["signup"] == mode


def test_an_unknown_team_setting_stops_the_start_before_storage_is_touched(tmp_path, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_TEAMS", "herkes")
    with pytest.raises(ValueError, match="VOLLEYSTAT_TEAMS"):
        create_app(tmp_path / "hic-yok", analyzer="none")
    assert not (tmp_path / "hic-yok").exists()


def test_an_unknown_gate_setting_stops_the_start_before_storage_is_touched(tmp_path, monkeypatch):
    monkeypatch.setenv("VOLLEYSTAT_SIGNUP", "herkes")
    with pytest.raises(ValueError, match="VOLLEYSTAT_SIGNUP"):
        create_app(tmp_path / "hic-yok", analyzer="none")
    assert not (tmp_path / "hic-yok").exists()


def test_the_hash_pool_closes_with_the_app(storage, open_signup):
    app = create_app(storage, analyzer="none")
    with TestClient(app):
        pass
    with pytest.raises(RuntimeError):
        app.state.hash_pool._executor.submit(lambda: None)


def test_the_admin_commands_create_the_storage_folder(tmp_path, capsys):
    from app import admin
    folder = tmp_path / "yeni-depo"
    assert admin.main(["--storage", str(folder), "signup-code"]) == 0
    assert (folder / "volleystat.sqlite3").exists()
    assert oct(folder.stat().st_mode & 0o777) == "0o700"


def test_the_database_keeps_one_account_per_username(storage):
    TestClient(create_app(storage, analyzer="none"))
    conn = sqlite3.connect(storage / "volleystat.sqlite3")
    try:
        conn.execute("INSERT INTO users(id, username, display_name, password_hash, created_at, password_changed_at, signup_via)"
                     " VALUES('a', 'ayse.k', 'A', 'x', 't', 't', 'OPEN')")
        with pytest.raises(sqlite3.IntegrityError):
            conn.execute("INSERT INTO users(id, username, display_name, password_hash, created_at, password_changed_at, signup_via)"
                         " VALUES('b', 'ayse.k', 'B', 'x', 't', 't', 'OPEN')")
    finally:
        conn.close()


def test_a_password_that_is_common_in_another_case_is_common(api):
    # The list holds "Good123654"; the lowercase form is common too.
    response = register(api, password="good123654")
    assert error(response)["details"]["reason"] == "COMMON"


def test_a_deletion_whose_session_ended_while_it_hashed_deletes_nothing(storage, open_signup, monkeypatch):
    with TestClient(create_app(storage, analyzer="none")) as client:
        register(client)
        thief = token_of(login(client))
        hold_hash(monkeypatch, "verify_password", lambda: accounts.reset_password(storage / "volleystat.sqlite3", "ayse.k"))
        late = client.post("/v1/me/delete", headers=bearer(thief), json={"password": PASSWORD})
        assert late.status_code == 401
        assert rows(storage, "SELECT COUNT(*) FROM users") == [(1,)]


def test_a_deletion_waits_for_a_short_reader_before_it_empties_the_log(api, storage):
    body = register(api, username="silinecek.kisi", display_name="Silinecek Kişi").json()
    reader = sqlite3.connect(storage / "volleystat.sqlite3", check_same_thread=False)
    reader.execute("BEGIN")
    reader.execute("SELECT COUNT(*) FROM users").fetchall()
    finished = threading.Timer(0.3, reader.rollback)
    finished.start()
    try:
        assert api.post("/v1/me/delete", headers=bearer(body["session"]["token"]), json={"password": PASSWORD}).status_code == 204
        finished.join()
        files = b"".join(path.read_bytes() for path in storage.glob("volleystat.sqlite3*"))
        for trace in ("silinecek.kisi", "Silinecek Kişi"):
            assert trace.encode() not in files, trace
    finally:
        reader.close()
