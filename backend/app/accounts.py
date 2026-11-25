"""Accounts: registration, login, sessions and the /v1/me routes (docs/API_V1.md, section 10; ADR-0119).

The server keeps a username, a display name and a password digest per person: no e-mail and no
phone number. Tokens are random and the server stores only their SHA-256 digests. Hashing runs on
its own small pool so a crowd of logins never holds up the rest of the API.
"""
from __future__ import annotations

import asyncio
import base64
import contextlib
import gzip
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
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from functools import lru_cache
from pathlib import Path
from typing import Any, Callable

from fastapi import APIRouter, Depends, Request, Response, Security
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel, ConfigDict, Field, model_validator

from . import deletion
from .db import connector, empty_write_ahead_log, migrate, new_id
from .errors import fail, fail_with_headers

log = logging.getLogger(__name__)

# scrypt cost. A stored digest names its own cost, so a change of the cost locks nobody out: an
# account answers at its stored cost until its next login, which stores a digest of the new cost.
SCRYPT_N = 2 ** 15
SCRYPT_R = 8
SCRYPT_P = 3

SESSION_IDLE = timedelta(days=30)
SESSION_MAX = timedelta(days=180)
# last_used_at is written at most this often, so reading a session rarely writes.
SESSION_TOUCH = timedelta(hours=1)
DEVICE_IDLE = timedelta(days=180)
OPERATOR_CODE_LIFE = timedelta(days=7)
# The operator's temporary password logs in for this long; after that only a new reset helps.
TEMPORARY_PASSWORD_LIFE = timedelta(days=7)

HASH_WORKERS = 2
HASH_QUEUE = 16

# Letters and digits that can not be mistaken for one another: no 0, 1, I, L or O.
CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
OPERATOR_CODE_LENGTH = 16

SIGNUP_MODES = {"link": "LINK", "open": "OPEN"}

USERNAME = re.compile(r"[a-z][a-z0-9._]{2,29}")
DISPLAY_NAME_MARKS = frozenset(" .'-")
# Common passwords of 10 to 128 characters from SecLists (MIT): the 10 000 most common passwords and
# the xato.net list of one million. Shorter ones need no list: the length rule refuses them.
COMMON_PASSWORDS = Path(__file__).parent / "wordlists" / "common_passwords.txt.gz"
# A password made of fewer different characters than this is refused (aaaaaaaaaa, 1212121212).
MIN_DISTINCT_CHARACTERS = 4


def now() -> datetime:
    return datetime.now(timezone.utc)


def iso(moment: datetime) -> str:
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_time(text: str) -> datetime:
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)


def digest(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def settings() -> str:
    """The sign-up gate, read from the environment: OPEN unless the operator set VOLLEYSTAT_SIGNUP=link.

    VOLLEYSTAT_TEAMS (B1's team-creation gate) is no longer read. A start script that still sets it
    gets one warning and a server that starts, because a refusal would stop the server for nothing.
    """
    signup = os.environ.get("VOLLEYSTAT_SIGNUP", "open").strip().lower()
    if signup not in SIGNUP_MODES:
        raise ValueError(f"VOLLEYSTAT_SIGNUP must be one of {sorted(SIGNUP_MODES)}, not {signup!r}")
    if "VOLLEYSTAT_TEAMS" in os.environ:
        log.warning("VOLLEYSTAT_TEAMS is no longer read: every account may create a team (ADR-0120)")
    return SIGNUP_MODES[signup]


# --- what the person gives ------------------------------------------------------------------------

def username_ok(username: str) -> bool:
    return USERNAME.fullmatch(username) is not None


def clean_display_name(text: str) -> str | None:
    """The display name as stored, or None when it breaks the rule.

    NFC, trimmed, runs of spaces made one, then 2 to 40 characters of letters, digits, spaces and
    . ' -, with at least one letter or digit. Every other character is refused, control and format
    characters (bidi and zero-width marks) included.
    """
    text = unicodedata.normalize("NFC", text)
    text = " ".join(part for part in text.split(" ") if part)
    if not 2 <= len(text) <= 40:
        return None
    if not all(unicodedata.category(char).startswith("L") or unicodedata.category(char) == "Nd"
               or char in DISPLAY_NAME_MARKS for char in text):
        return None
    if not any(char.isalnum() for char in text):
        return None
    return text


def comparable(text: str) -> str:
    """Text as the password checks compare it: NFKC, casefolded, without marks, the dotless ı as i.

    Casefolding the Turkish İ leaves an i with a combining dot, and the list and usernames are
    plain ASCII, so without this step "İLKER" would not contain "ilker".
    """
    folded = unicodedata.normalize("NFKC", text).casefold()
    plain = "".join(char for char in unicodedata.normalize("NFKD", folded) if unicodedata.category(char) != "Mn")
    return plain.replace("ı", "i")


@lru_cache(maxsize=1)
def common_passwords() -> frozenset[str]:
    with gzip.open(COMMON_PASSWORDS, "rt", encoding="utf-8") as lines:
        return frozenset(comparable(line.rstrip("\n")) for line in lines if line.strip())


def password_problem(password: str, username: str) -> str | None:
    """Why a password is refused, or None. Counted after NFKC and compared as comparable() writes it."""
    text = unicodedata.normalize("NFKC", password)
    if len(text) < 10:
        return "TOO_SHORT"
    if len(text) > 128:
        return "TOO_LONG"
    folded = comparable(text)
    if username and username in folded:
        return "CONTAINS_USERNAME"
    if len(set(folded)) < MIN_DISTINCT_CHARACTERS:
        return "REPETITIVE"
    if folded in common_passwords():
        return "COMMON"
    return None


# --- password digests -----------------------------------------------------------------------------

def _b64(raw: bytes) -> str:
    return base64.urlsafe_b64encode(raw).rstrip(b"=").decode()


def _unb64(text: str) -> bytes:
    return base64.urlsafe_b64decode(text + "=" * (-len(text) % 4))


def _scrypt(password: bytes, salt: bytes, n: int) -> bytes:
    # scrypt needs 128 * r * n bytes for its main table; twice that leaves room for the rest.
    return hashlib.scrypt(password, salt=salt, n=n, r=SCRYPT_R, p=SCRYPT_P, dklen=32,
                          maxmem=max(64 * 1024 * 1024, 2 * 128 * SCRYPT_R * n))


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


def outdated_cost(stored: str) -> bool:
    """True when a digest was made with another cost than the current one."""
    return stored.split("$")[1:4] != [str(SCRYPT_N), str(SCRYPT_R), str(SCRYPT_P)]


def dummy_hash() -> str:
    """A digest no password matches. Checking against it costs what a real check costs."""
    return f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${_b64(os.urandom(16))}${_b64(os.urandom(32))}"


class HashPool:
    """Two threads for scrypt and at most 16 waiting hashes; one more is refused with 503 at once."""

    def __init__(self, workers: int = HASH_WORKERS, queue: int = HASH_QUEUE):
        self._executor = ThreadPoolExecutor(max_workers=workers, thread_name_prefix="password-hash")
        self._slots = threading.BoundedSemaphore(workers + queue)

    @contextlib.asynccontextmanager
    async def slot(self):
        """Hold one of the slots, or answer 503 at once when every slot is taken."""
        if not self._slots.acquire(blocking=False):
            fail_with_headers("SERVER_BUSY", "Too many password checks are waiting", 503, {"Retry-After": "1"})
        try:
            yield
        finally:
            self._slots.release()

    async def compute(self, function: Callable[..., Any], *arguments: Any) -> Any:
        """Run a hash on the pool's own threads. The caller holds a slot."""
        return await asyncio.get_running_loop().run_in_executor(self._executor, function, *arguments)

    async def run(self, function: Callable[..., Any], *arguments: Any) -> Any:
        async with self.slot():
            return await self.compute(function, *arguments)

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
    """Keys of a login without a known phone: per username and source, and per username."""
    return [(f"login:user:{username}:{source}", BACKOFF, "USER"), (f"login:user:{username}", WINDOW, "USER")]


def account_keys(user_id: str) -> list[tuple[str, str, str]]:
    """The key of a password check inside a session (password change, account deletion).

    Only a person who holds a session of the account reaches it, so a stranger who locks the
    username at the login can not block the owner's own password change or deletion.
    """
    return [(f"password:user:{user_id}", BACKOFF, "ACCOUNT")]


def device_keys(device_hash: str) -> list[tuple[str, str, str]]:
    return [(f"login:device:{device_hash}", BACKOFF, "DEVICE")]


class Limiter:
    """Failure counts and reservations in process memory: nothing about addresses or usernames reaches the disk.

    BACKOFF: five failures are free, then each failure locks the key for 30 s, doubling up to
    15 min; the count is forgotten an hour after the last failure or on a success.
    WINDOW: 50 failures within an hour lock the key for 15 min.
    Reservations (reserve, release) count actions rather than failures, such as the teams an account
    creates in a day: a key holds at most `limit` places within its window, and an action that ended
    without its result gives its place back (design 3.6; B6a's open sign-up key uses the same rule).
    The limiter holds at most `max_keys` keys. It never forgets a locked key to make room, and it
    forgets the key with the fewest recent failures first, so a flood of new keys can not wash out
    the count of a key under attack. A reservation key counts its places of the window as recent
    failures, and it is locked while it holds `limit` of them.
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
        with self._lock:
            return self._wait(keys, at)

    def begin(self, keys: list[tuple[str, str, str]], at: float) -> tuple[int, str] | None:
        """Check the keys and count this attempt as a failure in one step.

        Returns the longest lock, or None after the attempt was counted. A right password then
        clears the count with succeed(). Attempts that arrive together each see the ones before
        them, so a burst of guesses can not all pass the check before the first one is counted.
        """
        with self._lock:
            wait = self._wait(keys, at)
            if wait is None:
                self._fail(keys, at)
            return wait

    def fail(self, keys: list[tuple[str, str, str]], at: float) -> None:
        with self._lock:
            self._fail(keys, at)

    def succeed(self, keys: list[tuple[str, str, str]]) -> None:
        with self._lock:
            for name, _rule, _scope in keys:
                self._entries.pop(name, None)

    def reserve(self, name: str, limit: int, window: float, scope: str, at: float) -> tuple[int, str] | None:
        """Check the key and take one of its `limit` places in one locked step.

        Returns None after the place was taken, or (seconds, scope) while the key holds `limit` places
        from the last `window` seconds: the wait runs until the oldest of them is `window` old. A refusal
        takes no place. Actions that arrive together each see the places of the ones before them.
        """
        with self._lock:
            entry = self._entries.pop(name, None) or self._new_entry()
            held = [moment for moment in entry["reserved"] if at - moment < window]
            entry.update(reserved=held, window=window, limit=limit)
            # Re-inserting moves the key to the end: among equal counts the oldest key stands first.
            self._entries[name] = entry
            if len(held) >= limit:
                return math.ceil(min(held) + window - at), scope
            held.append(at)
            self._make_room(at, {name})
            return None

    def release(self, name: str, at: float) -> None:
        """Give back the place that reserve() took at `at`: the action ended without its result."""
        with self._lock:
            entry = self._entries.get(name)
            if entry is not None and at in entry["reserved"]:
                entry["reserved"].remove(at)

    def _wait(self, keys: list[tuple[str, str, str]], at: float) -> tuple[int, str] | None:
        longest = None
        for name, _rule, scope in keys:
            entry = self._entries.get(name)
            if entry is not None and entry["until"] > at:
                seconds = math.ceil(entry["until"] - at)
                if longest is None or seconds > longest[0]:
                    longest = (seconds, scope)
        return longest

    @staticmethod
    def _new_entry() -> dict[str, Any]:
        return {"count": 0, "last": 0.0, "times": [], "until": 0.0, "reserved": [], "window": 0.0, "limit": 0}

    def _fail(self, keys: list[tuple[str, str, str]], at: float) -> None:
        written = set()
        for name, rule, _scope in keys:
            entry = self._entries.pop(name, None) or self._new_entry()
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
            # Re-inserting moves the key to the end: among equal counts the oldest key stands first.
            self._entries[name] = entry
            written.add(name)
        self._make_room(at, written)

    @staticmethod
    def _places(entry: dict[str, Any], at: float) -> int:
        return sum(1 for moment in entry["reserved"] if at - moment < entry["window"])

    @classmethod
    def _recent_failures(cls, entry: dict[str, Any], at: float) -> int:
        count = entry["count"] if at - entry["last"] < FORGET_AFTER else 0
        return count + sum(1 for moment in entry["times"] if at - moment < FORGET_AFTER) + cls._places(entry, at)

    @classmethod
    def _locked(cls, entry: dict[str, Any], at: float) -> bool:
        return entry["until"] > at or (entry["limit"] > 0 and cls._places(entry, at) >= entry["limit"])

    def _make_room(self, at: float, written: set[str]) -> None:
        # The limiter grows by a key's first failure or first reservation. A first failure never locks a
        # key, so there is an unlocked key to forget: at worst the one just written. A first reservation
        # locks its key when its limit is 1; when every key is then locked, the new key is not tracked.
        while len(self._entries) > self.max_keys:
            victim, fewest = None, None
            for name, entry in self._entries.items():
                if self._locked(entry, at):
                    continue
                failures = self._recent_failures(entry, at)
                if fewest is None or failures < fewest:
                    victim, fewest = name, failures
            if victim is None:
                victim = next(name for name in self._entries if name in written)
            if victim in written and time.monotonic() - self._full_logged_at > 60:
                self._full_logged_at = time.monotonic()
                log.warning("The limiter is full; a new key is not tracked")
            del self._entries[victim]


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
    if address.version == 6 and address.ipv4_mapped is not None:
        # An IPv4 client on a dual-stack socket: count it by its IPv4 address, not by ::/64.
        address = address.ipv4_mapped
    if address.is_loopback:
        return "local"
    if address.version == 6:
        return str(ipaddress.ip_network(f"{address}/64", strict=False))
    return str(address)


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
    """Store a new one-time code of `kind` and return it as four groups of four (XXXX-XXXX-XXXX-XXXX).

    Only SIGNUP codes exist since v5 of the accounts design. Another kind raises before the database
    is created or written.
    """
    if kind != "SIGNUP":
        raise ValueError(f"Only SIGNUP operator codes exist, not {kind!r}")
    migrate(db_path)
    plain = "".join(secrets.choice(CODE_ALPHABET) for _ in range(OPERATOR_CODE_LENGTH))
    moment = now()
    with connector(db_path)(write=True) as db:
        db.execute("INSERT INTO operator_codes(code_hash, kind, created_at, expires_at) VALUES(?,?,?,?)",
                   (digest(plain), kind, iso(moment), iso(moment + OPERATOR_CODE_LIFE)))
    return "-".join(plain[index:index + 4] for index in range(0, OPERATOR_CODE_LENGTH, 4))


def reset_password(db_path: Path, username: str) -> str | None:
    """Give `username` a temporary password for 7 days and close every session of the account.

    Returns the temporary password, or None for an unknown username. The operator hands it over in
    person (until recovery codes exist). The app suggests a new password after such a login. The
    phones of the account stay known: a device token opens nothing by itself, and it lets the
    owner's phone log in while a stranger who knows the username keeps the username locked.
    """
    migrate(db_path)
    temporary = "-".join("".join(secrets.choice(CODE_ALPHABET) for _ in range(4)) for _ in range(4))
    stored = hash_password(temporary)
    moment = now()
    with connector(db_path)(write=True) as db:
        user = db.execute("SELECT id FROM users WHERE username=?", (username,)).fetchone()
        if user is None:
            return None
        db.execute("UPDATE users SET password_hash=?, password_temporary_until=?, password_changed_at=? WHERE id=?",
                   (stored, iso(moment + TEMPORARY_PASSWORD_LIFE), iso(moment), user["id"]))
        db.execute("DELETE FROM sessions WHERE user_id=?", (user["id"],))
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
            "createdAt": user["created_at"], "passwordTemporary": user["password_temporary_until"] is not None}


def bearer_token(request: Request) -> str | None:
    header = request.headers.get("authorization", "")
    scheme, _, token = header.partition(" ")
    token = token.strip()
    if scheme.lower() != "bearer" or not token or len(token) > 100:
        return None
    return token


def not_signed_in() -> None:
    fail_with_headers("AUTH_REQUIRED", "A valid session is required", 401, {"WWW-Authenticate": "Bearer"})


def login_failed() -> None:
    fail_with_headers("LOGIN_FAILED", "The username or the password is wrong", 401, {"WWW-Authenticate": "Bearer"})


def password_rejected(reason: str) -> None:
    fail("PASSWORD_REJECTED", "The password does not meet the rule", 422, reason=reason)


# --- sessions -------------------------------------------------------------------------------------

# Names the session in the OpenAPI document of every route that needs one. current_user reads the
# header itself, so a missing or wrong token gets the API's own AUTH_REQUIRED answer, not FastAPI's.
SESSION_SCHEME = HTTPBearer(auto_error=False, description="The session token from register or login")


def live_session(db, hashed: str, moment: datetime):
    """The session row joined with its user, or None when the session is gone or ended."""
    row = db.execute("SELECT s.last_used_at, s.expires_at, u.* FROM sessions s JOIN users u ON u.id = s.user_id"
                     " WHERE s.token_hash=?", (hashed,)).fetchone()
    if row is None or moment >= parse_time(row["expires_at"]) or moment >= parse_time(row["last_used_at"]) + SESSION_IDLE:
        return None
    return row


def signed_in(connect, hashed: str, moment: datetime):
    """The user of a session, or 401. Reading a session writes at most once an hour.

    A route that writes reads the session once more inside its own write transaction
    (still_signed_in), so a session that ended while the route waited (for a hash, say) can not
    write anything.
    """
    with connect() as db:
        row = live_session(db, hashed, moment)
    if row is None:
        with connect(write=True) as db:
            db.execute("DELETE FROM sessions WHERE token_hash=? AND (expires_at <= ? OR last_used_at <= ?)",
                       (hashed, iso(moment), iso(moment - SESSION_IDLE)))
        not_signed_in()
    if moment - parse_time(row["last_used_at"]) >= SESSION_TOUCH:
        with connect(write=True) as db:
            db.execute("UPDATE sessions SET last_used_at=? WHERE token_hash=?", (iso(moment), hashed))
    return row


def still_signed_in(db, hashed: str, moment: datetime):
    """The session's user read again inside the caller's transaction, or 401."""
    row = live_session(db, hashed, moment)
    if row is None:
        not_signed_in()
    return row


@dataclass(frozen=True, eq=False)
class Caller:
    """The account behind a request, as current_user read it."""
    id: str
    signup_via: str
    token_hash: str
    user: Any  # the session row joined with its user (sqlite3.Row)


def current_user_dependency(connect) -> Callable[..., Caller]:
    """The dependency every route but health, register and login declares.

    FastAPI runs it before it validates the path, the query, the headers and the fields of the body,
    and before a route streams its body, so a request without a valid session gets 401 before
    anything else. Only BodyLimit's 413 and the 422 of a JSON body that does not parse come earlier,
    and neither reads the database.
    """
    def current_user(request: Request,
                     _credentials: HTTPAuthorizationCredentials | None = Security(SESSION_SCHEME)) -> Caller:
        token = bearer_token(request)
        if token is None:
            not_signed_in()
        hashed = digest(token)
        row = signed_in(connect, hashed, now())
        return Caller(row["id"], row["signup_via"], hashed, row)
    return current_user


def router(connect, db_path: Path, signup_mode: str, limiter: Limiter, pool: HashPool,
           current_user: Callable[..., Caller], files: deletion.MediaFiles, release: Callable[[str], None],
           uptime: Callable[[Any], float]) -> APIRouter:
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

    async def check_own_password(user, password: str, moment: datetime) -> None:
        """403 PASSWORD_WRONG on a wrong password, counted under the account's own key.

        The attempt is counted before the hash runs and cleared when the password is right.
        """
        keys = account_keys(user["id"])
        async with pool.slot():
            wait = limiter.begin(keys, moment.timestamp())
            if wait is not None:
                too_many(wait)
            right = await pool.compute(verify_password, password, user["password_hash"])
        if not right:
            fail("PASSWORD_WRONG", "The password is wrong", 403)
        limiter.succeed(keys)

    @routes.post("/v1/auth/register", status_code=201)
    async def register(payload: RegisterInput, request: Request):
        """Open an account. The gate comes first, so nothing about the username leaks while it is closed."""
        moment = now()
        # The gate: an invite link (later slices), an operator code, or nothing while sign-up is open (the
        # default; the operator closes it with VOLLEYSTAT_SIGNUP=link). A wrong code never opens an OPEN account.
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
            fail("SIGNUP_CLOSED", "Registration needs an invite link or an operator code", 403, reason="NO_LINK")
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
                        "DELETE FROM operator_codes WHERE code_hash=? AND kind='SIGNUP'", (code_hash,)).rowcount != 1:
                    # Another registration used the code in the meantime. (Its expiry was checked at this
                    # request's moment above, so a check here would add nothing.)
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
        async with pool.slot():
            # Counted before the hash, so guesses sent together can not all pass the check.
            wait = limiter.begin(keys, moment.timestamp())
            if wait is not None:
                too_many(wait)
            matches = await pool.compute(verify_password, payload.password,
                                         user["password_hash"] if user is not None else dummy_hash())
        temporary_until = user["password_temporary_until"] if user is not None else None
        if user is None or not matches or (temporary_until is not None and moment >= parse_time(temporary_until)):
            login_failed()
        limiter.succeed(keys)
        # A digest of an older cost is replaced by one of the current cost while the password is at hand.
        renewed = await pool.run(hash_password, payload.password) if outdated_cost(user["password_hash"]) else None

        def open_() -> dict[str, Any]:
            with connect(write=True) as db:
                current = db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone()
                if current is None or current["password_hash"] != user["password_hash"]:
                    # The account was deleted or its password changed while the hash ran.
                    login_failed()
                if renewed is not None:
                    db.execute("UPDATE users SET password_hash=? WHERE id=?", (renewed, user["id"]))
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
    def logout(caller: Caller = Depends(current_user)):
        moment = now()
        with connect(write=True) as db:
            still_signed_in(db, caller.token_hash, moment)
            db.execute("DELETE FROM sessions WHERE token_hash=?", (caller.token_hash,))
        return Response(status_code=204)

    @routes.get("/v1/me")
    def me(caller: Caller = Depends(current_user)):
        """The caller's account and teams. The teams are the list GET /v1/teams answers."""
        # Imported here: the teams module imports this one.
        from . import teams
        with connect() as db:
            return {"user": user_payload(caller.user), "teams": teams.teams_of(db, caller.id)}

    @routes.put("/v1/me/display-name")
    def change_display_name(payload: DisplayNameInput, caller: Caller = Depends(current_user)):
        moment = now()
        display_name = clean_display_name(payload.displayName)
        if display_name is None:
            fail("DISPLAY_NAME_INVALID", "The display name does not meet the rule", 422)
        with connect(write=True) as db:
            user = still_signed_in(db, caller.token_hash, moment)
            db.execute("UPDATE users SET display_name=? WHERE id=?", (display_name, user["id"]))
            return {"user": user_payload(db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone())}

    @routes.put("/v1/me/password")
    async def change_password(payload: PasswordChangeInput, caller: Caller = Depends(current_user)):
        """Set a new password. Every other session of the account ends and every phone is forgotten."""
        moment = now()
        user = caller.user
        await check_own_password(user, payload.currentPassword, moment)
        problem = password_problem(payload.newPassword, user["username"])
        if problem is not None:
            password_rejected(problem)
        stored = await pool.run(hash_password, payload.newPassword)
        own = caller.token_hash

        def change() -> dict[str, Any]:
            with connect(write=True) as db:
                # The session may have ended and the password may have changed while the hashes ran.
                still_signed_in(db, own, moment)
                if db.execute("UPDATE users SET password_hash=?, password_temporary_until=NULL, password_changed_at=?"
                              " WHERE id=? AND password_hash=?",
                              (stored, iso(moment), user["id"], user["password_hash"])).rowcount != 1:
                    fail("PASSWORD_WRONG", "The password is wrong", 403)
                db.execute("DELETE FROM sessions WHERE user_id=? AND token_hash<>?", (user["id"], own))
                db.execute("DELETE FROM login_devices WHERE user_id=?", (user["id"],))
                return {"user": user_payload(db.execute("SELECT * FROM users WHERE id=?", (user["id"],)).fetchone())}

        return await run_in_threadpool(change)

    @routes.post("/v1/me/delete", status_code=204)
    async def delete_account(payload: PasswordInput, caller: Caller = Depends(current_user)):
        """Delete the account with its personal matches: their uploads, videos, analyses and bytes,
        then the user row, its sessions and its phones. Nothing of it stays in the file.

        Matches that belong to no account (LEGACY) stay; the operator deletes them.
        """
        moment = now()
        user = caller.user
        await check_own_password(user, payload.password, moment)

        def delete() -> list[str]:
            with connect(write=True) as db:
                # The session may have ended and the password may have changed while the hash ran. Both
                # are checked before anything is deleted, because unlinked bytes can not come back.
                still_signed_in(db, caller.token_hash, moment)
                if db.execute("SELECT 1 FROM users WHERE id=? AND password_hash=?",
                              (user["id"], user["password_hash"])).fetchone() is None:
                    fail("PASSWORD_WRONG", "The password is wrong", 403)
                owned = [row["id"] for row in db.execute(
                    "SELECT id FROM matches WHERE owner_user_id=? ORDER BY rowid", (user["id"],))]
                at = uptime(db)
                # A verification in progress is about to turn an upload into a video of one of these
                # matches: the same refusal as DELETE /v1/matches/{id}, before any row or file goes.
                if any(deletion.finalizing(db, match_id, at) for match_id in owned):
                    fail("UPLOAD_FINALIZING", "An upload of this account is being verified", 409)
                removed = [deletion.delete_match_rows(db, files, match_id) for match_id in owned]
                # No row names the deleted account afterwards (design 3.7, step 5).
                db.execute("UPDATE uploads SET started_by=NULL WHERE started_by=?", (user["id"],))
                db.execute("DELETE FROM users WHERE id=?", (user["id"],))
                # The bytes go before the commit: a deletion must never leave a file behind.
                for each in removed:
                    each.unlink()
            empty_write_ahead_log(db_path)
            return [job_id for each in removed for job_id in each.pending_job_ids]

        pending = await run_in_threadpool(delete)
        # A run in progress has lost its job row and can write nothing; this only makes it stop sooner.
        for job_id in pending:
            release(job_id)
        return Response(status_code=204)

    return routes
