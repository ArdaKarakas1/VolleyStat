"""SQLite persistence for the single-process local pilot."""

import hashlib
import json
import os
import re
import sqlite3
import tempfile
from contextlib import closing, contextmanager
from pathlib import Path
from uuid import UUID, uuid4

from .domain import tally, validate_event

MAX_VIDEO_BYTES = 4 * 1024 ** 3
REVIEWABLE = ("needs_review", "reviewed")


class Problem(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def identifier(value):
    try:
        if str(UUID(value, version=4)) == value:
            return value
    except (TypeError, ValueError, AttributeError):
        pass
    raise Problem(400, "Geçersiz kayıt kimliği.")


def match_data(row):
    result = dict(row)
    result["video"] = json.loads(result["video"]) if result["video"] else None
    return result


def event_data(row):
    result = json.loads(row["payload"])
    return {**result, **{key: row[key] for key in ("id", "status", "source", "revision")}}


class Store:
    def __init__(self, directory):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.media = self.directory / "media"
        self.media.mkdir(exist_ok=True, mode=0o700)
        self.database = self.directory / "volleystat.sqlite3"
        with closing(sqlite3.connect(self.database)) as db, db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS matches (
                    id TEXT PRIMARY KEY, home TEXT NOT NULL, away TEXT NOT NULL,
                    state TEXT NOT NULL, size INTEGER, sha256 TEXT, video TEXT,
                    mode TEXT, error TEXT
                );
                CREATE TABLE IF NOT EXISTS events (
                    match_id TEXT NOT NULL REFERENCES matches(id),
                    id TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL,
                    source TEXT NOT NULL, revision INTEGER NOT NULL,
                    rally_key TEXT,
                    PRIMARY KEY (match_id, id),
                    UNIQUE (match_id, rally_key)
                );
            """)
            db.execute("UPDATE matches SET state='queued' WHERE state='analyzing'")
        for partial in self.media.glob("*.part"):
            partial.unlink()

    @contextmanager
    def connect(self, write=False):
        with closing(sqlite3.connect(self.database)) as db, db:
            db.row_factory = sqlite3.Row
            db.execute("PRAGMA foreign_keys=ON")
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield db

    def path(self, match_id):
        return self.media / f"{identifier(match_id)}.video"

    def match(self, db, match_id):
        row = db.execute("SELECT * FROM matches WHERE id=?", (identifier(match_id),)).fetchone()
        if row is None:
            raise Problem(404, "Maç bulunamadı.")
        return match_data(row)

    def create(self, match_id, data):
        names = [data.get(key) for key in ("home", "away")]
        if any(not isinstance(name, str) or not 1 <= len(name.strip()) <= 60 for name in names):
            raise Problem(400, "Her iki takımın adını girin (1–60 karakter).")
        home, away = (name.strip() for name in names)
        if home.casefold() == away.casefold():
            raise Problem(400, "Takım adları birbirinden farklı olmalı.")
        with self.connect(write=True) as db:
            db.execute(
                "INSERT OR IGNORE INTO matches (id,home,away,state) VALUES (?,?,?,'awaiting_upload')",
                (identifier(match_id), home, away),
            )
            match = self.match(db, match_id)
            if (home, away) != (match["home"], match["away"]):
                raise Problem(409, "Bu maç farklı takım adlarıyla oluşturulmuş.")
            return match

    def snapshot(self, match_id):
        with self.connect() as db:
            match = self.match(db, match_id)
            rows = db.execute("SELECT * FROM events WHERE match_id=?", (match_id,))
            events = sorted(
                (event_data(row) for row in rows if row["status"] != "deleted"),
                key=lambda event: (event["at_ms"], event["id"]),
            )
            return {**match, "events": events, "statistics": tally(events)}

    def upload(self, match_id, stream, length, checksum):
        if not 0 < length <= MAX_VIDEO_BYTES:
            raise Problem(413, "Video boyutu 1 bayt ile 4 GiB arasında olmalı.")
        if not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise Problem(400, "SHA-256 başlığı eksik veya geçersiz.")
        destination = self.path(match_id)
        with self.connect() as db:
            existing = self.match(db, match_id)
            if existing["size"] is not None:
                self.check_upload(existing, length, checksum)
        partial = None
        try:
            digest = hashlib.sha256()
            with tempfile.NamedTemporaryFile(dir=self.media, suffix=".part", delete=False) as file:
                partial = Path(file.name)
                remaining = length
                while remaining:
                    block = stream.read(min(remaining, 1024 * 1024))
                    if not block:
                        raise Problem(400, "Video aktarımı yarıda kesildi.")
                    remaining -= len(block)
                    digest.update(block)
                    file.write(block)
                if digest.hexdigest() != checksum:
                    raise Problem(422, "Video sağlama toplamı eşleşmedi.")
                file.flush()
                os.fsync(file.fileno())
            with self.connect(write=True) as db:
                existing = self.match(db, match_id)
                if existing["size"] is not None:
                    return self.check_upload(existing, length, checksum)
                os.replace(partial, destination)
                db.execute(
                    "UPDATE matches SET size=?, sha256=?, state='queued' WHERE id=?",
                    (length, checksum, match_id),
                )
                return self.match(db, match_id)
        finally:
            if partial is not None:
                partial.unlink(missing_ok=True)

    @staticmethod
    def check_upload(match, length, checksum):
        if (match["size"], match["sha256"]) != (length, checksum):
            raise Problem(409, "Bu maça farklı bir video yüklenmiş.")
        return match

    def claim(self):
        with self.connect(write=True) as db:
            row = db.execute("SELECT id FROM matches WHERE state='queued' ORDER BY rowid LIMIT 1").fetchone()
            if row is None:
                return None
            db.execute("UPDATE matches SET state='analyzing' WHERE id=?", (row["id"],))
            return row["id"]

    def publish_analysis(self, match_id, video, result):
        if result.mode not in ("manual_only", "assisted") or (result.mode == "manual_only" and result.proposals):
            raise ValueError("Analiz modu ve öneriler birbiriyle uyumsuz.")
        proposals = [validate_event(event, video["duration_ms"]) for event in result.proposals]
        with self.connect(write=True) as db:
            db.execute(
                "UPDATE matches SET state='needs_review',video=?,mode=?,error=NULL WHERE id=?",
                (json.dumps(video), result.mode, match_id),
            )
            for event in proposals:
                db.execute(
                    "INSERT INTO events VALUES (?,?,?,'pending','model',1,NULL)",
                    (match_id, str(uuid4()), json.dumps(event)),
                )

    def fail(self, match_id, error):
        with self.connect(write=True) as db:
            db.execute("UPDATE matches SET state='failed',error=? WHERE id=?", (error, match_id))

    def retry(self, match_id):
        with self.connect(write=True) as db:
            match = self.match(db, match_id)
            if match["state"] == "failed":
                db.execute("UPDATE matches SET state='queued',error=NULL WHERE id=?", (match_id,))
            elif match["state"] not in ("queued", "analyzing"):
                raise Problem(409, "Bu durumda analiz yeniden başlatılamaz.")
            return self.match(db, match_id)

    def save_event(self, match_id, event_id, data):
        identifier(event_id)
        action = data.get("action")
        if action not in ("verify", "reject"):
            raise Problem(400, "Geçersiz inceleme işlemi.")
        revision = data.get("revision")
        if type(revision) is not int or revision < 0:
            raise Problem(400, "Geçersiz olay sürümü.")
        status = "verified" if action == "verify" else "deleted"
        with self.connect(write=True) as db:
            match = self.match(db, match_id)
            if match["state"] not in REVIEWABLE:
                raise Problem(409, "Video henüz incelemeye hazır değil.")
            row = db.execute("SELECT * FROM events WHERE match_id=? AND id=?", (match_id, event_id)).fetchone()
            if status == "deleted" and not row:
                raise Problem(404, "Olay bulunamadı.")
            if status == "verified":
                try:
                    event = validate_event(data.get("event"), match["video"]["duration_ms"])
                except ValueError as error:
                    raise Problem(400, str(error)) from error
            else:
                event = json.loads(row["payload"])
            if row and row["revision"] == revision + 1 and row["status"] == status and json.loads(row["payload"]) == event:
                return event_data(row)
            if (row["revision"] if row else 0) != revision or (row and row["status"] == "deleted"):
                raise Problem(409, "Olay değişmiş. Güncel maç verisini yükleyin.")
            key = f'{event["set"]}:{event["rally"]}' if status == "verified" and event["kind"] == "rally" else None
            try:
                db.execute("""
                    INSERT INTO events VALUES (?,?,?,?,?,?,?)
                    ON CONFLICT (match_id,id) DO UPDATE SET payload=excluded.payload,
                    status=excluded.status, revision=excluded.revision, rally_key=excluded.rally_key
                """, (match_id, event_id, json.dumps(event), status, row["source"] if row else "manual", revision + 1, key))
            except sqlite3.IntegrityError as error:
                raise Problem(409, "Bu set ve ralli için bir sayı zaten doğrulanmış.") from error
            db.execute("UPDATE matches SET state='needs_review' WHERE id=?", (match_id,))
            return event_data(db.execute("SELECT * FROM events WHERE match_id=? AND id=?", (match_id, event_id)).fetchone())

    def complete(self, match_id):
        with self.connect(write=True) as db:
            match = self.match(db, match_id)
            if match["state"] not in REVIEWABLE:
                raise Problem(409, "Video henüz incelemeye hazır değil.")
            counts = {row[0]: row[1] for row in db.execute(
                "SELECT status,COUNT(*) FROM events WHERE match_id=? GROUP BY status", (match_id,)
            )}
            if counts.get("pending") or not counts.get("verified"):
                raise Problem(409, "İncelemeyi bitirmek için önerileri çözün ve en az bir olayı doğrulayın.")
            db.execute("UPDATE matches SET state='reviewed' WHERE id=?", (match_id,))
            return self.match(db, match_id)
