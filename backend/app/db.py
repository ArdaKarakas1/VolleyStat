"""SQLite schema and transactions shared by the routes and the analysis worker."""
from __future__ import annotations

import sqlite3
import uuid
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path

from .errors import fail

SCHEMA = """
CREATE TABLE IF NOT EXISTS matches (
  id TEXT PRIMARY KEY, home_team TEXT NOT NULL, away_team TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  name TEXT, played_on TEXT, venue TEXT, kind TEXT NOT NULL DEFAULT 'TRAINING'
);
CREATE TABLE IF NOT EXISTS uploads (
  id TEXT PRIMARY KEY, match_id TEXT NOT NULL REFERENCES matches(id),
  file_name TEXT NOT NULL, mime_type TEXT NOT NULL, expected_size INTEGER NOT NULL,
  next_offset INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'UPLOADING',
  video_id TEXT UNIQUE, created_at TEXT NOT NULL DEFAULT (datetime('now')),
  client_sha256 TEXT, finalize_started_at REAL
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
  idempotency_key TEXT,
  -- run_token names the one worker run that may write this job; heartbeat_at proves it is alive.
  run_token TEXT, heartbeat_at REAL, recoveries INTEGER NOT NULL DEFAULT 0,
  revision INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL, started_at TEXT, finished_at TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS analysis_jobs_one_active
  ON analysis_jobs(video_id) WHERE state IN ('QUEUED', 'PROCESSING');
CREATE UNIQUE INDEX IF NOT EXISTS analysis_jobs_idempotency
  ON analysis_jobs(video_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS analysis_jobs_match ON analysis_jobs(match_id);

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
  created_at TEXT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS review_decisions_idempotency
  ON review_decisions(card_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS review_decisions_job ON review_decisions(job_id);
CREATE TRIGGER IF NOT EXISTS review_decisions_append_only BEFORE UPDATE ON review_decisions
BEGIN SELECT RAISE(ABORT, 'review_decisions is append-only'); END;
"""

# Storage folders created by earlier previews lack the newer columns.
ADDED_COLUMNS = (
    ("matches", (("name", "TEXT"), ("played_on", "TEXT"), ("venue", "TEXT"),
                 ("kind", "TEXT NOT NULL DEFAULT 'TRAINING'"))),
    ("uploads", (("client_sha256", "TEXT"), ("finalize_started_at", "REAL"))),
    ("videos", (("reject_code", "TEXT"), ("media_json", "TEXT"), ("warnings_json", "TEXT"),
                ("probe_version", "TEXT"), ("storage_key", "TEXT"))),
)


def new_id() -> str:
    return str(uuid.uuid4())


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def migrate(db_path: Path) -> None:
    setup = sqlite3.connect(db_path, timeout=30)
    try:
        setup.execute("PRAGMA journal_mode=WAL")
        setup.executescript(SCHEMA)
        for table, columns in ADDED_COLUMNS:
            present = {row[1] for row in setup.execute(f"PRAGMA table_info({table})")}
            for column, kind in columns:
                if column not in present:
                    setup.execute(f"ALTER TABLE {table} ADD COLUMN {column} {kind}")
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


def find_video(db, video_id: str):
    record = db.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone()
    if not record:
        fail("VIDEO_NOT_FOUND", "Video does not exist", 404)
    return record
