"""SQLite schema and transactions shared by the routes and the analysis worker."""
from __future__ import annotations

import logging
import sqlite3
import time
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from .errors import fail

log = logging.getLogger(__name__)

# How long a deletion waits for a reader before it gives up emptying the write-ahead log: each
# attempt waits CHECKPOINT_WAIT in SQLite's busy handler, CHECKPOINT_ATTEMPTS times, about 3 s in all.
CHECKPOINT_ATTEMPTS = 20
CHECKPOINT_WAIT = 0.1
CHECKPOINT_PAUSE = 0.05

# No comment inside a CREATE TABLE below may hold a comma. An older SQLite that drops a column
# walks back from that column to the previous comma, and a comma inside a comment cuts the table
# definition there. The tests drop columns to rebuild the storage of earlier versions.
SCHEMA = """
CREATE TABLE IF NOT EXISTS matches (
  id TEXT PRIMARY KEY, home_team TEXT NOT NULL, away_team TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  name TEXT, played_on TEXT, venue TEXT, kind TEXT NOT NULL DEFAULT 'TRAINING',
  -- The rule the sets of this match are counted with (report.MatchRules). The defaults are FIVB.
  sets_to_win INTEGER NOT NULL DEFAULT 3, set_target INTEGER NOT NULL DEFAULT 25,
  deciding_set_target INTEGER NOT NULL DEFAULT 15,
  -- PERSONAL for a match an account opened or LEGACY for a row stored before accounts
  status TEXT NOT NULL DEFAULT 'LEGACY',
  -- The account that owns a PERSONAL match. No foreign key: account deletion removes the matches itself
  owner_user_id TEXT
);
CREATE TABLE IF NOT EXISTS uploads (
  id TEXT PRIMARY KEY, match_id TEXT NOT NULL REFERENCES matches(id),
  file_name TEXT NOT NULL, mime_type TEXT NOT NULL, expected_size INTEGER NOT NULL,
  next_offset INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'UPLOADING',
  video_id TEXT UNIQUE, created_at TEXT NOT NULL DEFAULT (datetime('now')),
  client_sha256 TEXT, finalize_started_at REAL,
  -- A random token that complete writes with its FINALIZING mark. It starts with the mark of the server's run.
  -- The request owns the verification while the row holds its token: a takeover after 15 minutes writes a new
  -- one and the start of the next run clears it. A FINALIZING row without a token is abandoned
  finalize_token TEXT,
  -- The wall clock moment of the last initiate or stored chunk. Information only: no decision reads its value
  -- and only an empty touch (this and touched_uptime NULL) marks a row stored before server 0.9
  touched_at REAL,
  -- The server's uptime (server_clock) at the last initiate or stored chunk. The sweep aborts an upload
  -- once the uptime is 24 h past it
  touched_uptime REAL,
  -- The server's uptime when complete wrote its FINALIZING mark. The sweep closes the verification 24 h
  -- of uptime later. complete may take it over 15 minutes of uptime later and at once when it has no token
  finalize_started_uptime REAL,
  -- The account that started the upload or NULL once that account is deleted
  started_by TEXT
);
CREATE TABLE IF NOT EXISTS videos (
  id TEXT PRIMARY KEY, match_id TEXT NOT NULL REFERENCES matches(id),
  upload_id TEXT NOT NULL UNIQUE REFERENCES uploads(id),
  size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, mime_type TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'STORED_UNVALIDATED',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  reject_code TEXT, media_json TEXT, warnings_json TEXT, probe_version TEXT,
  storage_key TEXT
);

-- Everything derived from a video hangs off its jobs and is removed with the video (ON DELETE CASCADE).
CREATE TABLE IF NOT EXISTS analysis_jobs (
  id TEXT PRIMARY KEY,
  video_id TEXT NOT NULL REFERENCES videos(id) ON DELETE CASCADE,
  match_id TEXT NOT NULL REFERENCES matches(id),
  state TEXT NOT NULL,
  stage TEXT, progress_done INTEGER, progress_total INTEGER, progress_unit TEXT,
  analyzer_id TEXT NOT NULL, analyzer_version TEXT NOT NULL, analyzer_kind TEXT NOT NULL,
  failure_code TEXT, failure_message TEXT,
  -- run_token names the one worker run that may write this job; heartbeat_at proves it is alive.
  run_token TEXT, heartbeat_at REAL, recoveries INTEGER NOT NULL DEFAULT 0,
  revision INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT,
  -- rally_list_version moves with every correction of the rally list and every winner decision
  -- and with every change of the match rule. The list counts as complete only while
  -- list_confirmed_version equals it.
  rally_list_version INTEGER NOT NULL DEFAULT 0,
  list_confirmed_version INTEGER, list_confirmed_at TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS analysis_jobs_one_active
  ON analysis_jobs(video_id) WHERE state IN ('QUEUED', 'PROCESSING');
CREATE INDEX IF NOT EXISTS analysis_jobs_match ON analysis_jobs(match_id);
-- Every Idempotency-Key that was answered with a job: the request that created the job and each
-- request that was answered with it while it was pending.
CREATE TABLE IF NOT EXISTS analysis_request_keys (
  video_id TEXT NOT NULL, idempotency_key TEXT NOT NULL,
  job_id TEXT NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
  PRIMARY KEY (video_id, idempotency_key)
);

-- What the analyzer proposed, written once when its run ends. No correction ever changes a row.
CREATE TABLE IF NOT EXISTS rally_proposals (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
  start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
  proposed_winner TEXT NOT NULL, confidence REAL, uncertainty_json TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS rally_proposals_job ON rally_proposals(job_id);
CREATE TRIGGER IF NOT EXISTS rally_proposals_append_only BEFORE UPDATE ON rally_proposals
BEGIN SELECT RAISE(ABORT, 'rally_proposals is append-only'); END;
CREATE TRIGGER IF NOT EXISTS rally_proposals_keep_rows BEFORE DELETE ON rally_proposals
WHEN EXISTS (SELECT 1 FROM analysis_jobs WHERE id = OLD.job_id)
BEGIN SELECT RAISE(ABORT, 'rally_proposals is append-only'); END;

-- The working list of rallies. start_ms, end_ms and sequence follow the user's corrections;
-- proposal_id names the proposal a rally came from and is NULL for a rally the user made.
-- proposed_winner and confidence are the model's proposal. status, winner, terminal_action and
-- scorer_jersey are written by user decisions only.
CREATE TABLE IF NOT EXISTS rallies (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
  sequence INTEGER NOT NULL,
  start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL,
  clip_start_ms INTEGER NOT NULL, clip_end_ms INTEGER NOT NULL,
  proposed_winner TEXT NOT NULL, confidence REAL, uncertainty_json TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'PROPOSED',
  winner TEXT, terminal_action TEXT, scorer_jersey INTEGER,
  proposal_id TEXT,
  UNIQUE (job_id, sequence)
);
CREATE TABLE IF NOT EXISTS review_cards (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
  rally_id TEXT NOT NULL REFERENCES rallies(id) ON DELETE CASCADE,
  question_type TEXT NOT NULL,
  status TEXT NOT NULL DEFAULT 'OPEN', answer TEXT,
  version INTEGER NOT NULL DEFAULT 1,
  UNIQUE (rally_id, question_type)
);
CREATE INDEX IF NOT EXISTS review_cards_job ON review_cards(job_id);

-- Audit trail. card_id is not a foreign key: a row must outlive a card that a later decision removed.
CREATE TABLE IF NOT EXISTS review_decisions (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
  rally_id TEXT NOT NULL, card_id TEXT NOT NULL, question_type TEXT NOT NULL,
  action TEXT NOT NULL,
  previous_value TEXT, new_value TEXT,
  idempotency_key TEXT, response_json TEXT,
  created_at TEXT NOT NULL,
  -- Set on rows that a correction of the rally list wrote: the answers it removed or reopened.
  correction_id TEXT
);
-- A key names one decision request on the whole server. The decision route checks that inside
-- its write transaction; storage written before that rule may hold a key on two cards.
CREATE INDEX IF NOT EXISTS review_decisions_key
  ON review_decisions(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS review_decisions_job ON review_decisions(job_id);
CREATE TRIGGER IF NOT EXISTS review_decisions_append_only BEFORE UPDATE ON review_decisions
BEGIN SELECT RAISE(ABORT, 'review_decisions is append-only'); END;
-- Rows leave only together with their job (deleted video). The cascade runs after the job row is
-- gone, so the WHEN clause lets it through and stops every other DELETE.
CREATE TRIGGER IF NOT EXISTS review_decisions_keep_rows BEFORE DELETE ON review_decisions
WHEN EXISTS (SELECT 1 FROM analysis_jobs WHERE id = OLD.job_id)
BEGIN SELECT RAISE(ABORT, 'review_decisions is append-only'); END;

-- Audit trail of the user's corrections of the rally list: what was added, moved, split, merged
-- or confirmed, with the rallies as they were before and after. rally ids are not foreign keys:
-- a row must outlive a rally that a later correction removed. These rows are a record of what
-- one user did. They are not labels and never enter a model metric or a training set.
CREATE TABLE IF NOT EXISTS rally_corrections (
  id TEXT PRIMARY KEY,
  job_id TEXT NOT NULL REFERENCES analysis_jobs(id) ON DELETE CASCADE,
  operation TEXT NOT NULL,
  rally_id TEXT, created_rally_id TEXT, removed_rally_id TEXT,
  before_json TEXT NOT NULL, after_json TEXT NOT NULL,
  list_version_after INTEGER NOT NULL,
  created_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS rally_corrections_job ON rally_corrections(job_id);
CREATE TRIGGER IF NOT EXISTS rally_corrections_append_only BEFORE UPDATE ON rally_corrections
BEGIN SELECT RAISE(ABORT, 'rally_corrections is append-only'); END;
CREATE TRIGGER IF NOT EXISTS rally_corrections_keep_rows BEFORE DELETE ON rally_corrections
WHEN EXISTS (SELECT 1 FROM analysis_jobs WHERE id = OLD.job_id)
BEGIN SELECT RAISE(ABORT, 'rally_corrections is append-only'); END;

-- The uptime counter of the sweep (sweep.py). Only the time this server runs counts as idle time of an
-- upload: a phone can not send a chunk to a server that is off. The counter never reads the wall clock.
CREATE TABLE IF NOT EXISTS server_clock (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  -- Seconds this server has run summed over its life: written at every pass of the sweep and at a stop
  uptime REAL NOT NULL
);

-- Accounts (ADR-0119). The server keeps no e-mail and no phone number.
CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  -- Lowercase ASCII exactly as stored. Login compares it exactly.
  username TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  -- scrypt$N$r$p$salt$key with salt and key in base64url
  password_hash TEXT NOT NULL,
  -- Set when the operator reset the password: the temporary password logs in until then and the app suggests a new one.
  password_temporary_until TEXT,
  created_at TEXT NOT NULL,
  password_changed_at TEXT NOT NULL,
  -- How the account opened: INVITE or OPEN_MATCH or SIGNUP_CODE or OPEN (no link and no code). Only SIGNUP_CODE gets the larger video quotas.
  signup_via TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS sessions (
  -- SHA-256 hex of the bearer token. The token itself is never stored.
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  -- Written at most once an hour. A session ends 30 days after its last use.
  last_used_at TEXT NOT NULL,
  -- 180 days after created_at however often the session is used.
  expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_user ON sessions(user_id);
-- Phones that logged in. A login with a valid device token counts its failures apart from strangers.
CREATE TABLE IF NOT EXISTS login_devices (
  -- SHA-256 hex of the device token. The token itself is never stored.
  token_hash TEXT PRIMARY KEY,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  last_used_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS login_devices_user ON login_devices(user_id);
-- One-time codes the operator prints. A registration removes the SIGNUP code it used. Since v5 only SIGNUP codes exist.
CREATE TABLE IF NOT EXISTS operator_codes (
  -- SHA-256 hex of the code without dashes
  code_hash TEXT PRIMARY KEY,
  -- SIGNUP opens one registration. B1 reserved CAPTAIN for v4's captain code and v5 removed it.
  kind TEXT NOT NULL,
  -- Unused since v5. B1 created it for the captain code and older storage folders keep it.
  signup_used INTEGER NOT NULL DEFAULT 0,
  -- Unused since v5 (as above)
  used_by_user_id TEXT REFERENCES users(id) ON DELETE CASCADE,
  -- Unused since v5 (as above)
  team_name TEXT,
  created_at TEXT NOT NULL,
  expires_at TEXT NOT NULL
);

-- Teams (ADR-0120). Any signed-in account creates one (design 4.1).
CREATE TABLE IF NOT EXISTS teams (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  -- Four characters of the code alphabet that the server draws at random
  tag TEXT NOT NULL UNIQUE,
  -- 1 for Monday to 7 for Sunday or NULL
  match_weekday INTEGER,
  -- HH:MM or NULL
  match_time TEXT,
  -- Lossy WebP of 256 px for members or NULL
  logo BLOB,
  -- Lossy WebP of 128 px for link views or NULL
  logo_small BLOB,
  logo_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS memberships (
  team_id TEXT NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  -- CAPTAIN or MEMBER
  role TEXT NOT NULL DEFAULT 'MEMBER',
  -- STARTER or SUBSTITUTE: the seat this member holds in the team's pool. No default: every insert names it.
  seat TEXT NOT NULL CHECK (seat IN ('STARTER', 'SUBSTITUTE')),
  -- 1 to 99 or NULL
  jersey INTEGER,
  joined_at TEXT NOT NULL,
  -- The captain's mark for the new-member badge. Members who joined later count as new.
  joins_seen_at TEXT,
  PRIMARY KEY (team_id, user_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS memberships_one_captain ON memberships(team_id) WHERE role = 'CAPTAIN';
CREATE UNIQUE INDEX IF NOT EXISTS memberships_jersey ON memberships(team_id, jersey) WHERE jersey IS NOT NULL;
CREATE INDEX IF NOT EXISTS memberships_user ON memberships(user_id);

-- The unit of the join engine: one per team and one per open match (2.1)
CREATE TABLE IF NOT EXISTS seat_pools (
  id TEXT PRIMARY KEY,
  -- TEAM or OPEN_MATCH
  kind TEXT NOT NULL,
  -- The team of a TEAM pool and NULL otherwise
  team_id TEXT UNIQUE REFERENCES teams(id) ON DELETE CASCADE,
  -- The open match record of an OPEN_MATCH pool and NULL otherwise
  match_id TEXT UNIQUE REFERENCES matches(id) ON DELETE CASCADE,
  -- Seats of each type with the captain or a playing organiser counted (2.3)
  starter_seats INTEGER NOT NULL,
  substitute_seats INTEGER NOT NULL,
  -- The queue number the next claim takes. Only a write transaction moves it.
  queue_seq INTEGER NOT NULL DEFAULT 0,
  -- Moves by one in every write transaction that changes the join state of the pool. The snapshot carries it.
  queue_version INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  CHECK ((kind = 'TEAM' AND team_id IS NOT NULL AND match_id IS NULL)
      OR (kind = 'OPEN_MATCH' AND match_id IS NOT NULL AND team_id IS NULL)),
  -- v5: six on the court. A team always has 6 starter seats and an open match 12 (R1 and R2).
  -- The upper bound of the substitute seats lives in the seats engine and the creation routes (2.3).
  CHECK ((kind = 'TEAM' AND starter_seats = 6) OR (kind = 'OPEN_MATCH' AND starter_seats = 12)),
  CHECK (substitute_seats >= 0)
);

-- People a captain or an organiser removed. They cannot claim a seat of this pool until the block goes.
CREATE TABLE IF NOT EXISTS pool_blocks (
  pool_id TEXT NOT NULL REFERENCES seat_pools(id) ON DELETE CASCADE,
  user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TEXT NOT NULL,
  PRIMARY KEY (pool_id, user_id)
);
"""

# Storage folders created by earlier previews lack the newer columns.
ADDED_COLUMNS = (
    ("matches", (("name", "TEXT"), ("played_on", "TEXT"), ("venue", "TEXT"),
                 ("kind", "TEXT NOT NULL DEFAULT 'TRAINING'"),
                 # Version 0.5 counted every match with the FIVB rule, so the defaults keep its counts.
                 ("sets_to_win", "INTEGER NOT NULL DEFAULT 3"), ("set_target", "INTEGER NOT NULL DEFAULT 25"),
                 ("deciding_set_target", "INTEGER NOT NULL DEFAULT 15"),
                 # Every row stored before accounts becomes LEGACY: it belongs to no account (ADR-0119, B3).
                 ("status", "TEXT NOT NULL DEFAULT 'LEGACY'"), ("owner_user_id", "TEXT"))),
    ("uploads", (("client_sha256", "TEXT"), ("finalize_started_at", "REAL"), ("finalize_token", "TEXT"),
                 ("touched_at", "REAL"), ("started_by", "TEXT"), ("touched_uptime", "REAL"),
                 ("finalize_started_uptime", "REAL"))),
    ("videos", (("reject_code", "TEXT"), ("media_json", "TEXT"), ("warnings_json", "TEXT"),
                ("probe_version", "TEXT"), ("storage_key", "TEXT"))),
    ("analysis_jobs", (("rally_list_version", "INTEGER NOT NULL DEFAULT 0"), ("list_confirmed_version", "INTEGER"),
                       ("list_confirmed_at", "TEXT"))),
    ("rallies", (("proposal_id", "TEXT"),)),
    ("review_decisions", (("correction_id", "TEXT"),)),
)

# Indexes on added columns. They can not go into SCHEMA: migrate() runs SCHEMA before it adds the
# columns, and on older storage an index on a column that does not exist yet stops the start-up. These
# are the first indexes on added columns; a rebuild test drops them before it drops their columns.
ADDED_INDEXES = (
    "CREATE INDEX IF NOT EXISTS matches_owner ON matches(owner_user_id) WHERE owner_user_id IS NOT NULL",
    "CREATE INDEX IF NOT EXISTS matches_status ON matches(status)",
    "CREATE INDEX IF NOT EXISTS uploads_unfinished ON uploads(status) WHERE status IN ('UPLOADING', 'FINALIZING')",
    "CREATE INDEX IF NOT EXISTS uploads_started_by ON uploads(started_by) WHERE started_by IS NOT NULL",
)


def new_id() -> str:
    return str(uuid.uuid4())


def is_canonical_id(text: str) -> bool:
    """True for a UUID written the way new_id() writes one: lowercase, with dashes, nothing around it."""
    try:
        return str(uuid.UUID(text)) == text
    except ValueError:
        return False


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


# The builds of B3 before the uptime counter measured uptime with the wall clock (server_clock.last_alive and
# the time off in server_downtimes). Nothing there converts into uptime, so migrate() drops it and the counter
# starts at 0. Their columns uploads.kept_touched_at and kept_finalize_started_at stay unused: dropping a
# column needs SQLite 3.35, and a start must not fail on an older one.
EARLIER_CLOCK = (
    "DROP TABLE IF EXISTS server_downtimes",
    "DROP TABLE IF EXISTS server_downtimes_kept",
    "DROP INDEX IF EXISTS uploads_touched",
)

# An unfinished upload of an earlier build has a wall clock touch but no uptime. It counts as touched, and its
# verification as started, at the stored uptime of the first start of this build: a full day from there, late
# and never early. Such a verification holds no token of this run (0.8 wrote none, and the start frees an
# earlier build's), so the first complete may take it over at once. A row without any touch was stored before
# server 0.9, and the first pass of the sweep aborts it.
FIRST_UPTIME = (
    "UPDATE uploads SET touched_uptime = (SELECT COALESCE(MAX(uptime), 0) FROM server_clock)"
    " WHERE touched_uptime IS NULL AND touched_at IS NOT NULL AND status IN ('UPLOADING', 'FINALIZING')",
    "UPDATE uploads SET finalize_started_uptime = (SELECT COALESCE(MAX(uptime), 0) FROM server_clock)"
    " WHERE finalize_started_uptime IS NULL AND finalize_started_at IS NOT NULL AND status = 'FINALIZING'",
)


def migrate(db_path: Path) -> None:
    setup = sqlite3.connect(db_path, timeout=30)
    try:
        setup.execute("PRAGMA journal_mode=WAL")
        if any(row[1] == "last_alive" for row in setup.execute("PRAGMA table_info(server_clock)")):
            setup.execute("DROP TABLE server_clock")
        for statement in EARLIER_CLOCK:
            setup.execute(statement)
        setup.executescript(SCHEMA)
        for table, columns in ADDED_COLUMNS:
            present = {row[1] for row in setup.execute(f"PRAGMA table_info({table})")}
            for column, kind in columns:
                if column not in present:
                    setup.execute(f"ALTER TABLE {table} ADD COLUMN {column} {kind}")
        for statement in ADDED_INDEXES:
            setup.execute(statement)
        for statement in FIRST_UPTIME:
            setup.execute(statement)
        if any(row[1] == "idempotency_key" for row in setup.execute("PRAGMA table_info(analysis_jobs)")):
            # Earlier storage kept the key in a column of the job that the request created.
            setup.execute(
                "INSERT OR IGNORE INTO analysis_request_keys(video_id, idempotency_key, job_id)"
                " SELECT video_id, idempotency_key, id FROM analysis_jobs WHERE idempotency_key IS NOT NULL")
        # Storage written before corrections existed holds every rally exactly as the analyzer
        # proposed it, so the proposal record can still be written from the rally itself. A job
        # with corrections is never touched: its rallies without a proposal were made by the user.
        untouched = setup.execute(
            "SELECT id, job_id, start_ms, end_ms, proposed_winner, confidence, uncertainty_json FROM rallies"
            " WHERE proposal_id IS NULL AND job_id NOT IN (SELECT job_id FROM rally_corrections)").fetchall()
        for rally_id, *proposal in untouched:
            proposal_id = new_id()
            setup.execute(
                "INSERT INTO rally_proposals(id, job_id, start_ms, end_ms, proposed_winner, confidence, uncertainty_json)"
                " VALUES(?,?,?,?,?,?,?)", (proposal_id, *proposal))
            setup.execute("UPDATE rallies SET proposal_id=? WHERE id=?", (proposal_id, rally_id))
        setup.commit()
    finally:
        setup.close()


def connector(db_path: Path):
    """Return connect(write=False): one transaction per `with` block, committed on a clean exit."""

    @contextmanager
    def connect(write: bool = False):
        conn = sqlite3.connect(db_path, timeout=30, isolation_level=None)
        conn.row_factory = sqlite3.Row
        try:
            # Also what makes the ON DELETE CASCADE clauses above take effect.
            conn.execute("PRAGMA foreign_keys=ON")
            # A deleted row is overwritten with zeros. Without this its text stays readable in the
            # freed pages of the file, long after the server said the match was deleted.
            conn.execute("PRAGMA secure_delete=ON")
            # Readers take a snapshot and never wait for a writer (WAL).
            conn.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield conn
            conn.execute("COMMIT")
        except BaseException:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
            raise
        finally:
            conn.close()

    return connect


def empty_write_ahead_log(db_path: Path) -> None:
    """Fold the write-ahead log into the database file and cut the log to zero bytes.

    The log still holds the pages as they were before a deletion. Called after a deletion so that
    no copy of the deleted rows stays in it. When another connection is reading at this moment
    SQLite leaves the log as it is; it is then emptied by the next deletion, or when the last
    connection closes.
    """
    # A short wait: the deletion is already committed, and a reader must not hold its answer back.
    # A reader in the middle of a transaction makes the checkpoint answer busy, so it is tried a
    # few more times while such a reader finishes.
    conn = sqlite3.connect(db_path, timeout=CHECKPOINT_WAIT)
    try:
        for attempt in range(CHECKPOINT_ATTEMPTS):
            busy, _log_pages, _moved = conn.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()
            if not busy:
                return
            time.sleep(CHECKPOINT_PAUSE)
        log.warning("The write-ahead log stays until a reader finishes; the next deletion or the last connection empties it")
    except sqlite3.Error:
        log.exception("The write-ahead log could not be emptied")
    finally:
        conn.close()


def find_video(db, video_id: str):
    record = db.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone()
    if not record:
        fail("VIDEO_NOT_FOUND", "Video does not exist", 404)
    return record
