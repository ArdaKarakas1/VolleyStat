"""Accounts: registration, login, sessions and the /v1/me routes (docs/API_V1.md, section 10; ADR-0119).

The server keeps a username, a display name and a password digest per person: no e-mail and no
phone number. Tokens are random and the server stores only their SHA-256 digests. Hashing runs on
its own small pool so a crowd of logins never holds up the rest of the API.
"""
from __future__ import annotations

import asyncio
import base64
import hashlib
import hmac
import ipaddress
import logging
import math
import os
import re
import secrets
import threading
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Request, Response
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .db import connector, empty_write_ahead_log, migrate, new_id
from .errors import fail, fail_with_headers

log = logging.getLogger(__name__)

# scrypt cost. A stored digest names its own cost, so changing this locks nobody out.
SCRYPT_N = 2 ** 15
SCRYPT_R = 8
SCRYPT_P = 3
SCRYPT_MAXMEM = 64 * 1024 * 1024

SESSION_IDLE = timedelta(days=30)
SESSION_MAX = timedelta(days=180)
# last_used_at is written at most this often, so reading a session rarely writes.
SESSION_TOUCH = timedelta(hours=1)
DEVICE_IDLE = timedelta(days=180)
OPERATOR_CODE_LIFE = timedelta(days=7)

HASH_WORKERS = 2
HASH_QUEUE = 16

# Letters and digits that can not be mistaken for one another: no 0, 1, I, L or O.
CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
OPERATOR_CODE_LENGTH = 16

SIGNUP_MODES = {"link": "LINK", "open": "OPEN"}
TEAM_MODES = {"operator": "OPERATOR", "open": "OPEN"}

USERNAME = re.compile(r"[a-z][a-z0-9._]{2,29}")
DISPLAY_NAME_MARKS = frozenset(" .'-")
COMMON_PASSWORDS = Path(__file__).parent / "wordlists" / "common_passwords.txt"


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_time(text: str) -> datetime:
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def settings() -> tuple[str, str]:
    """The sign-up gate and the team-creation gate, read from the environment."""
    signup = os.environ.get("VOLLEYSTAT_SIGNUP", "link").strip().lower()
    teams = os.environ.get("VOLLEYSTAT_TEAMS", "operator").strip().lower()
    if signup not in SIGNUP_MODES:
        raise ValueError(f"VOLLEYSTAT_SIGNUP must be one of {sorted(SIGNUP_MODES)}, not {signup!r}")
    if teams not in TEAM_MODES:
        raise ValueError(f"VOLLEYSTAT_TEAMS must be one of {sorted(TEAM_MODES)}, not {teams!r}")
    return SIGNUP_MODES[signup], TEAM_MODES[teams]


# --- what the person gives ------------------------------------------------------------------------

def username_ok(username: str) -> bool:
    return USERNAME.fullmatch(username) is not None


def clean_display_name(text: str) -> str | None:
    """The display name as stored, or None when it breaks the rule.

    NFC, no control or format character anywhere (that covers bidi and zero-width marks), trimmed,
    runs of spaces made one, 2 to 40 characters of letters, digits, spaces and . ' -.
    """
    text = unicodedata.normalize("NFC", text)
    if any(unicodedata.category(char) in ("Cc", "Cf") for char in text):
        return None
    text = " ".join(part for part in text.split(" ") if part)
    if not 2 <= len(text) <= 40:
        return None
    if not all(unicodedata.category(char).startswith("L") or unicodedata.category(char) == "Nd"
               or char in DISPLAY_NAME_MARKS for char in text):
        return None
    if not any(char.isalnum() for char in text):
        return None
    return text


@lru_cache(maxsize=1)
def common_passwords() -> frozenset[str]:
    with COMMON_PASSWORDS.open(encoding="utf-8") as lines:
        return frozenset(line.strip().casefold() for line in lines if line.strip())


def password_problem(password: str, username: str) -> str | None:
    """Why a password is refused, or None. Counted and compared after NFKC."""
    text = unicodedata.normalize("NFKC", password)
    if len(text) < 10:
        return "TOO_SHORT"
    if len(text) > 128:
        return "TOO_LONG"
    folded = text.casefold()
    if username and username in folded:
        return "CONTAINS_USERNAME"
    if folded in common_passwords():
        return "COMMON"
    return None


# --- password digests -----------------------------------------------------------------------------

def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _scrypt(password: bytes, salt: bytes, n: int) -> bytes:
    return hashlib.scrypt(password, salt=salt, n=n, r=SCRYPT_R, p=SCRYPT_P, dklen=32, maxmem=SCRYPT_MAXMEM)


def _password_bytes(password: str) -> bytes:
    return unicodedata.normalize("NFKC", password).encode()


def hash_password(password: str) -> str:
    salt, n = os.urandom(16), SCRYPT_N
    return f"scrypt${n}${SCRYPT_R}${SCRYPT_P}${_b64(salt)}${_b64(_scrypt(_password_bytes(password), salt, n))}"


def verify_password(password: str, stored: str) -> bool:
    scheme, n, r, p, salt, key = stored.split("$")
    if (scheme, int(r), int(p)) != ("scrypt", SCRYPT_R, SCRYPT_P):
        raise ValueError("Unknown password digest")
    return hmac.compare_digest(_scrypt(_password_bytes(password), _unb64(salt), int(n)), _unb64(key))


def dummy_hash() -> str:
    """A digest no password matches. Checking against it costs what a real check costs."""
    return f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${_b64(os.urandom(16))}${_b64(os.urandom(32))}"


class HashPool:
    """Two threads for scrypt and at most 16 waiting hashes; one more is refused with 503 at once."""

    def __init__(self, workers: int = HASH_WORKERS, queue: int = HASH_QUEUE):
        self._executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="password-hash")
        self._slots = threading.BoundedSemaphore(workers + queue)

    async def run(self, function: Callable[..., Any], *arguments: Any) -> Any:
        if not self._slots.acquire(blocking=False):
            fail_with_headers("SERVER_BUSY", "Too many password checks are waiting", 503, {"Retry-After": "1"})
        try:
            return await asyncio.get_running_loop().run_in_executor(self._executor, function, *arguments)
        finally:
            self._slots.release()

    def close(self) -> None:
        self._executor.shutdown(wait=False, cancel_futures=True)


# --- throttling -----------------------------------------------------------------------------------

BACKOFF, WINDOW = "BACKOFF", "WINDOW"
FREE_FAILURES = 5
FIRST_WAIT = 30
LONGEST_WAIT = 900
FORGET_AFTER = 3600
WINDOW_FAILURES = 50


def login_keys(username: str, source: str) -> list[tuple[str, str, str]]:
    """Keys of a login (or a password check) without a known phone: per username and source, and per username."""
    return [(f"login:user:{username}:{source}", BACKOFF, "USER"), (f"login:user:{username}", WINDOW, "USER")]


def device_keys(device_hash: str) -> list[tuple[str, str, str]]:
    return [(f"login:device:{device_hash}", BACKOFF, "DEVICE")]


class Limiter:
    """Failure counts in process memory: nothing about addresses or usernames reaches the disk.

    BACKOFF: five failures are free, then each failure locks the key for 30 s, doubling up to
    15 min; the count is forgotten an hour after the last failure or on a success.
    WINDOW: 50 failures within an hour lock the key for 15 min.
    The limiter holds at most `max_keys` keys. It never forgets a locked key to make room.
    """

    def __init__(self, max_keys: int = 10_000):
        self.max_keys = max_keys
        self._entries: dict[str, dict[str, Any]] = {}
        self._lock = threading.Lock()
        self._full_logged_at = 0.0

    def __len__(self) -> int:
        return len(self._entries)

    def wait(self, keys: list[tuple[str, str, str]], at: float) -> tuple[int, str] | None:
        """The longest lock among `keys` as (seconds, scope), or None."""
        longest = None
        with self._lock:
            for name, _rule, scope in keys:
                entry = self._entries.get(name)
                if entry is not None and entry["until"] > at:
                    seconds = math.ceil(entry["until"] - at)
                    if longest is None or seconds > longest[0]:
                        longest = (seconds, scope)
        return longest

    def fail(self, keys: list[tuple[str, str, str]], at: float) -> None:
        with self._lock:
            for name, rule, _scope in keys:
                entry = self._entries.pop(name, None) or {"count": 0, "last": 0.0, "times": [], "until": 0.0}
                if rule == BACKOFF:
                    if at - entry["last"] >= FORGET_AFTER:
                        entry["count"] = 0
                    entry["count"] += 1
                    entry["last"] = at
                    if entry["count"] >= FREE_FAILURES:
                        entry["until"] = at + min(FIRST_WAIT * 2 ** (entry["count"] - FREE_FAILURES), LONGEST_WAIT)
                else:
                    entry["times"] = [moment for moment in entry["times"] if at - moment < FORGET_AFTER] + [at]
                    if len(entry["times"]) >= WINDOW_FAILURES:
                        entry["until"], entry["times"] = at + LONGEST_WAIT, []
                # Re-inserting moves the key to the end: the oldest keys stand first.
                self._entries[name] = entry
            self._make_room(at)

    def succeed(self, keys: list[tuple[str, str, str]]) -> None:
        with self._lock:
            for name, _rule, _scope in keys:
                self._entries.pop(name, None)

    def _make_room(self, at: float) -> None:
        while len(self._entries) > self.max_keys:
            oldest_unlocked = next((name for name, entry in self._entries.items() if entry["until"] <= at), None)
            if oldest_unlocked is None:
                if time.monotonic() - self._full_logged_at > 60:
                    self._full_logged_at = time.monotonic()
                    log.warning("Every slot of the login limiter holds a locked key; a new key is not tracked")
                # The newest key leaves: the limiter tracks no new key while every slot is locked.
                self._entries.pop(next(reversed(self._entries)))
                continue
            del self._entries[oldest_unlocked]


def source_of(request: Request) -> str:
    """Where a request came from, for the source keys. The loopback interface is the source "local".

    The server listens on the loopback interface only, so every request is local today. From the
    exposure slice a trusted proxy names the client; an IPv6 client counts by its /64.
    """
    host = request.client.host if request.client else None
    if host is None or host == "testclient":
        return "local"
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return "local"
    if address.is_loopback:
        return "local"
    if address.version == 6:
        return str(ipaddress.ip_network(f"{host}/64", strict=False))
    return host


def too_many(wait: tuple[int, str]) -> None:
    seconds, scope = wait
    fail_with_headers("TOO_MANY_ATTEMPTS", "Too many failed attempts; wait before trying again", 429,
                      {"Retry-After": str(seconds)}, retryAfterSeconds=seconds, scope=scope)


# --- operator codes -------------------------------------------------------------------------------

def normalize_operator_code(text: str) -> str | None:
    """The code as stored (uppercase, no spaces or dashes), or None when it can not be one."""
    plain = re.sub(r"[\s-]", "", text).upper()
    if len(plain) != OPERATOR_CODE_LENGTH or any(char not in CODE_ALPHABET for char in plain):
        return None
    return plain


def issue_operator_code(db_path: Path, kind: str) -> str:
    """Store a new one-time code of `kind` and return it as four groups of four (XXXX-XXXX-XXXX-XXXX)."""
    migrate(db_path)
    plain = "".join(secrets.choice(CODE_ALPHABET) for _ in range(OPERATOR_CODE_LENGTH))
    moment = now()
    with connector(db_path)(write=True) as db:
        db.execute("INSERT INTO operator_codes(code_hash, kind, created_at, expires_at) VALUES(?,?,?,?)",
                   (digest(plain), kind, iso(moment), iso(moment + OPERATOR_CODE_LIFE)))
    return "-".join(plain[index:index + 4] for index in range(0, OPERATOR_CODE_LENGTH, 4))


def reset_password(db_path: Path, username: str) -> str | None:
    """Give `username` a one-time temporary password, close every session and forget every phone.

    Returns the temporary password, or None for an unknown username. The operator hands it over in
    person (until recovery codes exist). The app suggests a new password after such a login.
    """
    migrate(db_path)
    temporary = "-".join("".join(secrets.choice(CODE_ALPHABET) for _ in range(4)) for _ in range(4))
    stored = hash_password(temporary)
    with connector(db_path)(write=True) as db:
        user = db.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone()
        if user is None:
            return None
        db.execute("UPDATE users SET password_hash=?, password_temporary=1, password_changed_at=? WHERE id=?",
                   (stored, iso(now()), user["id"]))
        db.execute("DELETE FROM sessions WHERE user_id=?", (user["id"],))
        db.execute("DELETE FROM login_devices WHERE user_id=?", (user["id"],))
    return temporary


# --- request bodies -------------------------------------------------------------------------------

class LinkReference(BaseModel):
    """A share link by its token or by its code: exactly one of the two."""
    model_config = ConfigDict(extra="forbid")

    token: str | None = Field(default=None, max_length=100)
    code: str | None = Field(default=None, max_length=40)

    @model_validator(mode="after")
    def exactly_one(self):
        if (self.token is None) == (self.code is None):
            raise ValueError("A link names either its token or its code")
        return self


class RegisterInput(BaseModel):
    # The fields are checked by hand after the sign-up gate, so a closed gate answers every
    # registration alike. The limits here only keep a request body small.
    model_config = ConfigDict(extra="forbid")

    username: str = Field(max_length=200)
    displayName: str = Field(max_length=200)
    password: str = Field(max_length=1024)
    link: LinkReference | None = None
    operatorCode: str | None = Field(default=None, max_length=64)


class LoginInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    username: str = Field(max_length=200)
    password: str = Field(max_length=1024)
    deviceToken: str | None = Field(default=None, max_length=100)


class DisplayNameInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    displayName: str = Field(max_length=200)


class PasswordChangeInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    currentPassword: str = Field(max_length=1024)
    newPassword: str = Field(max_length=1024)


class PasswordInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    password: str = Field(max_length=1024)


# --- payloads -------------------------------------------------------------------------------------

def user_payload(user) -> dict[str, Any]:
    return {"id": user["id"], "username": user["username"], "displayName": user["display_name"],
            "createdAt": user["created_at"], "passwordTemporary": bool(user["password_temporary"])}


def bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token or len(token) > 100:
        return None
    return token


def not_signed_in() -> None:
    fail_with_headers("AUTH_REQUIRED", "A valid session is required", 401, {"WWW-Authenticate": "Bearer"})


def password_rejected(reason: str) -> None:
    fail("PASSWORD_REJECTED", "The password does not meet the rule", 422, reason=reason)


def router(connect, db_path: Path, signup_mode: str, limiter: Limiter, pool: HashPool) -> APIRouter:
    routes = APIRouter()

    def open_session(db, user_id: str, moment: datetime) -> dict[str, str]:
        token = secrets.token_urlsafe(32)
        db.execute("INSERT INTO sessions(token_hash, user_id, created_at, last_used_at, expires_at) VALUES(?,?,?,?,?)",
                   (digest(token), user_id, iso(moment), iso(moment), iso(moment + SESSION_MAX)))
        return {"token": token, "expiresAt": iso(min(moment + SESSION_IDLE, moment + SESSION_MAX))}

    def remember_device(db, user_id: str, known_hash: str | None, moment: datetime) -> str | None:
        """Keep a valid device token of this account (None is returned: the phone keeps its own) or make one."""
        if known_hash is not None:
            db.execute("UPDATE login_devices SET last_used_at=? WHERE token_hash=?", (iso(moment), known_hash))
            return None
        token = secrets.token_urlsafe(16)
        db.execute("INSERT INTO login_devices(token_hash, user_id, created_at, last_used_at) VALUES(?,?,?,?)",
                   (digest(token), user_id, iso(moment), iso(moment)))
        return token

    def known_device(db, device_token: str | None, username: str, moment: datetime) -> str | None:
        """The digest of `device_token` when it is a phone that logged in to `username` within 180 days."""
        if not device_token:
            return None
        hashed = digest(device_token)
        row = db.execute("SELECT d.last_used_at, u.username FROM login_devices d JOIN users u ON u.id = d.user_id"
                         " WHERE d.token_hash=?", (hashed,)).fetchone()
        if row is None or row["username"] != username or moment - parse_time(row["last_used_at"]) >= DEVICE_IDLE:
            return None
        return hashed

    def signed_in(request: Request, moment: datetime):
        """The user of the request's session, or 401. Reading a session writes at most once an hour."""
        token = bearer_token(request)
        if token is None:
            not_signed_in()
        hashed = digest(token)
        with connect() as db:
            row = db.execute("SELECT s.last_used_at, s.expires_at, u.* FROM sessions s JOIN users u ON u.id = s.user_id"
                             " WHERE s.token_hash=?", (hashed,)).fetchone()
        if row is None:
            not_signed_in()
        last_used = parse_time(row["last_used_at"])
        if moment >= parse_time(row["expires_at"]) or moment >= last_used + SESSION_IDLE:
            with connect(write=True) as db:
                db.execute("DELETE FROM sessions WHERE token_hash=?", (hashed,))
            not_signed_in()
        if moment - last_used >= SESSION_TOUCH:
            with connect(write=True) as db:
                db.execute("UPDATE sessions SET last_used_at=? WHERE token_hash=?", (iso(moment), hashed))
        return row

    async def check_own_password(request: Request, user, password: str, moment: datetime) -> None:
        """403 PASSWORD_WRONG on a wrong password, counted like a failed login of the account."""
        keys = login_keys(user["username"], source_of(request))
        wait = limiter.wait(keys, moment.timestamp())
        if wait is not None:
            too_many(wait)
        if not await pool.run(verify_password, password, user["password_hash"]):
            limiter.fail(keys, moment.timestamp())
            fail("PASSWORD_WRONG", "The password is wrong", 403)
        limiter.succeed(keys)

    @routes.post("/v1/auth/register", status_code=201)
    async def register(payload: RegisterInput, request: Request):
        """Open an account. The gate comes first, so nothing about the username leaks while it is closed."""
        moment = now()
        # The gate: an invite link (later slices), an operator code or the open setting of tests.
        code_hash = None
        if payload.link is not None:
            fail("LINK_NOT_FOUND", "No live link has this token or code", 404)
        if payload.operatorCode is not None:
            plain = normalize_operator_code(payload.operatorCode)
            code_hash = digest(plain) if plain is not None else None

            def code_is_live() -> bool:
                with connect() as db:
                    return code_hash is not None and db.execute(
                        "SELECT 1 FROM operator_codes WHERE code_hash=? AND kind='SIGNUP' AND expires_at > ?",
                        (code_hash, iso(moment))).fetchone() is not None

            if not await run_in_threadpool(code_is_live):
                fail("SIGNUP_CODE_INVALID", "The operator code is not valid", 403)
        elif signup_mode != "OPEN":
            fail("SIGNUP_CLOSED", "Registration needs an invite link or an operator code", 403)
        # The fields, in the order the app shows them.
        if not username_ok(payload.username):
            fail("USERNAME_INVALID", "The username does not meet the rule", 422)
        display_name = clean_display_name(payload.displayName)
        if display_name is None:
            fail("DISPLAY_NAME_INVALID", "The display name does not meet the rule", 422)
        problem = password_problem(payload.password, payload.username)
        if problem is not None:
            password_rejected(problem)

        def taken() -> bool:
            with connect() as db:
                return db.execute("SELECT 1 FROM users WHERE username=?", (payload.username,)).fetchone() is not None

        # Checked before the hash so a taken username costs no hash; checked again in the write.
        if await run_in_threadpool(taken):
            fail("USERNAME_TAKEN", "The username is taken", 409)
        stored = await pool.run(hash_password, payload.password)

        def create() -> dict[str, Any]:
            with connect(write=True) as db:
                if code_hash is not None and db.execute(
                        "DELETE FROM operator_codes WHERE code_hash=? AND kind='SIGNUP' AND expires_at > ?",
                        (code_hash, iso(moment))).rowcount != 1:
                    # Another registration used the code in the meantime.
                    fail("SIGNUP_CODE_INVALID", "The operator code is not valid", 403)
                if db.execute("SELECT 1 FROM users WHERE username=?", (payload.username,)).fetchone():
                    fail("USERNAME_TAKEN", "The username is taken", 409)
                user_id = new_id()
                db.execute("INSERT INTO users(id, username, display_name, password_hash, created_at, password_changed_at,"
                           " signup_via) VALUES(?,?,?,?,?,?,?)",
                           (user_id, payload.username, display_name, stored, iso(moment), iso(moment),
                            "SIGNUP_CODE" if code_hash is not None else "OPEN"))
                session = open_session(db, user_id, moment)
                device = remember_device(db, user_id, None, moment)
                user = db.execute("SELECT * FROM users WHERE id=?", (user_id,)).fetchone()
                return {"user": user_payload(user), "session": session, "deviceToken": device}

        return await run_in_threadpool(create)

    @routes.post("/v1/auth/login")
    async def login(payload: LoginInput, request: Request):
        """Open a session. A wrong password and an unknown username get the same answer and cost the same."""
        moment = now()

        def look_up():
            with connect() as db:
                return (db.execute("SELECT * FROM users WHERE username=?", (payload.username,)).fetchone(),
                        known_device(db, payload.deviceToken, payload.username, moment))

        user, device_hash = await run_in_threadpool(look_up)
        keys = device_keys(device_hash) if device_hash is not None else login_keys(payload.username, source_of(request))
        wait = limiter.wait(keys, moment.timestamp())
        if wait is not None:
            too_many(wait)
        matches = await pool.run(verify_password, payload.password, user["password_hash"] if user is not None else dummy_hash())
        if user is None or not matches:
            limiter.fail(keys, moment.timestamp())
            fail_with_headers("LOGIN_FAILED", "The username or the password is wrong", 401, {"WWW-Authenticate": "Bearer"})
        limiter.succeed(keys)

        def open_() -> dict[str, Any]:
            with connect(write=True) as db:
                current = db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
                if current is None or current["password_hash"] != user["password_hash"]:
                    # The account was deleted or its password changed while the hash ran.
                    fail_with_headers("LOGIN_FAILED", "The username or the password is wrong", 401, {"WWW-Authenticate": "Bearer"})
                # Sessions that ended stay until their owner logs in again; then they go.
                db.execute("DELETE FROM sessions WHERE user_id=? AND (expires_at <= ? OR last_used_at <= ?)",
                           (user["id"], iso(moment), iso(moment - SESSION_IDLE)))
                db.execute("DELETE FROM login_devices WHERE user_id=? AND last_used_at <= ?",
                           (user["id"], iso(moment - DEVICE_IDLE)))
                session = open_session(db, user["id"], moment)
                device = remember_device(db, user["id"], device_hash, moment) or payload.deviceToken
                return {"user": user_payload(current), "session": session, "deviceToken": device}

        return await run_in_threadpool(open_)

    @routes.post("/v1/auth/logout", status_code=204)
    def logout(request: Request):
        signed_in(request, now())
        with connect(write=True) as db:
            db.execute("DELETE FROM sessions WHERE token_hash=?", (digest(bearer_token(request)),))
        return Response(status_code=204)

    @routes.get("/v1/me")
    def me(request: Request):
        user = signed_in(request, now())
        # Teams come with their own slice; until then a person belongs to none.
        return {"user": user_payload(user), "teams": []}

    @routes.put("/v1/me/display-name")
    def change_display_name(payload: DisplayNameInput, request: Request):
        user = signed_in(request, now())
        display_name = clean_display_name(payload.displayName)
        if display_name is None:
            fail("DISPLAY_NAME_INVALID", "The display name does not meet the rule", 422)
        with connect(write=True) as db:
            db.execute("UPDATE users SET display_name=? WHERE id=?", (display_name, user["id"]))
            return {"user": user_payload(db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone())}

    @routes.put("/v1/me/password")
    async def change_password(payload: PasswordChangeInput, request: Request):
        """Set a new password. Every other session of the account ends and every phone is forgotten."""
        moment = now()
        user = await run_in_threadpool(signed_in, request, moment)
        await check_own_password(request, user, payload.currentPassword, moment)
        problem = password_problem(payload.newPassword, user["username"])
        if problem is not None:
            password_rejected(problem)
        stored = await pool.run(hash_password, payload.newPassword)
        own = digest(bearer_token(request))

        def change() -> dict[str, Any]:
            with connect(write=True) as db:
                db.execute("UPDATE users SET password_hash=?, password_temporary=0, password_changed_at=? WHERE id=?",
                           (stored, iso(moment), user["id"]))
                db.execute("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", (user["id"], own))
                db.execute("DELETE FROM login_devices WHERE user_id=?", (user["id"],))
                return {"user": user_payload(db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone())}

        return await run_in_threadpool(change)

    @routes.post("/v1/me/delete", status_code=204)
    async def delete_account(payload: PasswordInput, request: Request):
        """Delete the account: the user row, its sessions and its phones. Nothing of it stays in the file."""
        moment = now()
        user = await run_in_threadpool(signed_in, request, moment)
        await check_own_password(request, user, payload.password, moment)

        def delete() -> None:
            with connect(write=True) as db:
                db.execute("DELETE FROM users WHERE id=?", (user["id"],))
            empty_write_ahead_log(db_path)

        await run_in_threadpool(delete)
        return Response(status_code=204)

    return routes
