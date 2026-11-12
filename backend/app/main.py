"""VolleyStat local-only development API; no trained AI is connected."""
from __future__ import annotations

import hashlib
import json
import os
import re
import sqlite3
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, model_validator

from . import media

CHUNK_SIZE = 4 * 1024 * 1024
MAX_VIDEO_BYTES = 4 * 1024 * 1024 * 1024
ALLOWED_MIMES = {"video/mp4", "video/quicktime", "video/webm", "video/x-matroska"}


def fail(code: str, message: str, status: int, **details: Any) -> None:
    raise HTTPException(status_code=status, detail={"code": code, "message": message, "details": details})


class MatchInput(BaseModel):
    homeTeamName: str = Field(min_length=2, max_length=80)
    awayTeamName: str = Field(min_length=2, max_length=80)

    @model_validator(mode="after")
    def different_teams(self):
        self.homeTeamName = self.homeTeamName.strip()
        self.awayTeamName = self.awayTeamName.strip()
        if not self.homeTeamName or not self.awayTeamName or self.homeTeamName.casefold() == self.awayTeamName.casefold():
            raise ValueError("Home and away teams must be different non-empty names")
        return self


class UploadInput(BaseModel):
    fileName: str = Field(min_length=1, max_length=255)
    sizeBytes: int = Field(gt=0, le=MAX_VIDEO_BYTES)
    mimeType: str
    rightsAttested: bool
    allowTraining: bool = False
    clientSha256: str | None = Field(default=None, pattern=r"^[0-9a-f]{64}$")

    @model_validator(mode="after")
    def verify_rights(self):
        if not self.rightsAttested:
            raise ValueError("Rights attestation is required")
        if self.allowTraining:
            raise ValueError("Training consent and provenance workflow is not implemented")
        if self.mimeType not in ALLOWED_MIMES:
            raise ValueError("Unsupported declared MIME type")
        if self.fileName != Path(self.fileName).name or "\\" in self.fileName or not re.search(r"\.(mp4|mov|webm|mkv)$", self.fileName, re.I):
            raise ValueError("Invalid video file name")
        return self


def new_id() -> str:
    return str(uuid.uuid4())


def create_app(storage_root: str | Path | None = None) -> FastAPI:
    storage = Path(storage_root or os.environ.get("VOLLEYSTAT_STORAGE") or (Path(__file__).parent.parent / "storage")).resolve()
    storage.mkdir(parents=True, exist_ok=True)
    (storage / "uploads").mkdir(exist_ok=True)
    (storage / "videos").mkdir(exist_ok=True)
    db_path = storage / "volleystat.sqlite3"

    @contextmanager
    def connect():
        conn = sqlite3.connect(db_path, timeout=30)
        conn.row_factory = sqlite3.Row
        try:
            conn.execute("PRAGMA foreign_keys=ON")
            conn.execute("BEGIN IMMEDIATE")
            yield conn
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()

    with connect() as db:
        db.executescript("""
        CREATE TABLE IF NOT EXISTS matches (
          id TEXT PRIMARY KEY, home_team TEXT NOT NULL, away_team TEXT NOT NULL,
          created_at TEXT NOT NULL DEFAULT (datetime('now'))
        );
        CREATE TABLE IF NOT EXISTS uploads (
          id TEXT PRIMARY KEY, match_id TEXT NOT NULL REFERENCES matches(id),
          file_name TEXT NOT NULL, mime_type TEXT NOT NULL, expected_size INTEGER NOT NULL,
          next_offset INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'UPLOADING',
          video_id TEXT UNIQUE, created_at TEXT NOT NULL DEFAULT (datetime('now')),
          client_sha256 TEXT
        );
        CREATE TABLE IF NOT EXISTS videos (
          id TEXT PRIMARY KEY, match_id TEXT NOT NULL REFERENCES matches(id),
          upload_id TEXT NOT NULL UNIQUE REFERENCES uploads(id),
          size_bytes INTEGER NOT NULL, sha256 TEXT NOT NULL, mime_type TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'STORED_UNVALIDATED',
          created_at TEXT NOT NULL DEFAULT (datetime('now')),
          reject_code TEXT, media_json TEXT, warnings_json TEXT, probe_version TEXT
        );
        """)
        # Storage folders created by the 0.1 preview lack the 0.2 columns.
        for table, columns in (
            ("uploads", ("client_sha256",)),
            ("videos", ("reject_code", "media_json", "warnings_json", "probe_version")),
        ):
            present = {row["name"] for row in db.execute(f"PRAGMA table_info({table})")}
            for column in columns:
                if column not in present:
                    db.execute(f"ALTER TABLE {table} ADD COLUMN {column} TEXT")
        # A finalize interrupted by a crash goes back to a resumable upload.
        db.execute("UPDATE uploads SET status='UPLOADING' WHERE status='FINALIZING'")

    def video_payload(record) -> dict[str, Any]:
        return {
            "id": record["id"], "matchId": record["match_id"], "status": record["status"],
            "sizeBytes": record["size_bytes"], "sha256": record["sha256"],
            "quality": quality_payload(record),
        }

    def quality_payload(record) -> dict[str, Any]:
        return {
            "videoId": record["id"], "status": record["status"],
            "rejectCode": record["reject_code"],
            "media": json.loads(record["media_json"]) if record["media_json"] else None,
            "warnings": json.loads(record["warnings_json"]) if record["warnings_json"] else [],
            "probeVersion": record["probe_version"],
            # Only the file format is checked. Nothing below has been measured.
            "sceneChecks": {
                name: {"value": None, "status": "NOT_MEASURED"}
                for name in ("courtVisible", "ballVisible", "cameraStable", "lighting")
            },
        }

    app = FastAPI(title="VolleyStat Local Development API", version="0.2.0",
                  description="LOCAL TEST DATA ONLY. No authentication, no trained AI. Never expose to the internet.")

    @app.exception_handler(HTTPException)
    async def http_error(_request: Request, exc: HTTPException):
        detail = exc.detail if isinstance(exc.detail, dict) else {"code": "HTTP_ERROR", "message": str(exc.detail), "details": {}}
        return JSONResponse(status_code=exc.status_code, content={"error": detail})

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, exc: RequestValidationError):
        return JSONResponse(status_code=422, content={"error": {"code": "VALIDATION_ERROR", "message": "Invalid request", "details": {"fields": [{"location": ".".join(map(str, e["loc"])), "message": e["msg"]} for e in exc.errors()]}}})

    @app.get("/v1/health")
    def health():
        return {"status": "ok", "mode": "local-dev", "ai": "not-configured",
                "mediaProbe": "available" if media.probe_available() else "unavailable"}

    @app.post("/v1/matches", status_code=201)
    def create_match(payload: MatchInput):
        match_id = new_id()
        with connect() as db:
            db.execute("INSERT INTO matches(id, home_team, away_team) VALUES(?,?,?)", (match_id, payload.homeTeamName, payload.awayTeamName))
        return {"id": match_id, "homeTeamName": payload.homeTeamName, "awayTeamName": payload.awayTeamName, "status": "DRAFT"}

    @app.get("/v1/matches/{match_id}")
    def get_match(match_id: str):
        with connect() as db:
            match = db.execute("SELECT * FROM matches WHERE id=?", (match_id,)).fetchone()
            if not match:
                fail("MATCH_NOT_FOUND", "Match does not exist", 404)
        return {"id": match["id"], "homeTeamName": match["home_team"], "awayTeamName": match["away_team"], "status": "DRAFT"}

    @app.post("/v1/matches/{match_id}/uploads", status_code=201)
    def initiate_upload(match_id: str, payload: UploadInput):
        upload_id = new_id()
        with connect() as db:
            if not db.execute("SELECT 1 FROM matches WHERE id=?", (match_id,)).fetchone():
                fail("MATCH_NOT_FOUND", "Match does not exist", 404)
            db.execute("INSERT INTO uploads(id,match_id,file_name,mime_type,expected_size,client_sha256) VALUES(?,?,?,?,?,?)", (upload_id, match_id, payload.fileName, payload.mimeType, payload.sizeBytes, payload.clientSha256))
            (storage / "uploads" / f"{upload_id}.part").touch(exist_ok=False)
        return {"uploadId": upload_id, "nextOffset": 0, "chunkSizeBytes": CHUNK_SIZE, "status": "UPLOADING"}

    @app.get("/v1/uploads/{upload_id}")
    def get_upload(upload_id: str):
        with connect() as db:
            record = db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
            if not record:
                fail("UPLOAD_NOT_FOUND", "Upload does not exist", 404)
        return {"uploadId": record["id"], "nextOffset": record["next_offset"], "sizeBytes": record["expected_size"], "status": record["status"], "videoId": record["video_id"]}

    @app.put("/v1/uploads/{upload_id}/chunks")
    async def upload_chunk(upload_id: str, request: Request, offset: int):
        if offset < 0:
            fail("INVALID_OFFSET", "Offset must be non-negative", 400)
        data = bytearray()
        async for block in request.stream():
            data += block
            if len(data) > CHUNK_SIZE:
                break
        if not data or len(data) > CHUNK_SIZE:
            fail("CHUNK_SIZE_INVALID", "Chunk must contain 1..4 MiB", 413)
        with connect() as db:
            record = db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
            if not record:
                fail("UPLOAD_NOT_FOUND", "Upload does not exist", 404)
            if record["status"] != "UPLOADING":
                fail("UPLOAD_CLOSED", "Upload is not active", 409)
            if offset != record["next_offset"]:
                fail("OFFSET_MISMATCH", "Expected a different offset", 409, expectedOffset=record["next_offset"])
            if offset + len(data) > record["expected_size"]:
                fail("UPLOAD_OVERFLOW", "Chunk exceeds declared size", 413)
            part = storage / "uploads" / f"{upload_id}.part"
            if not part.exists() or part.stat().st_size != offset:
                fail("UPLOAD_FILE_MISMATCH", "Stored bytes do not match upload offset", 409)
            with part.open("r+b") as fh:
                fh.seek(offset)
                fh.write(data)
                fh.flush()
                os.fsync(fh.fileno())
            new_offset = offset + len(data)
            db.execute("UPDATE uploads SET next_offset=? WHERE id=?", (new_offset, upload_id))
        return {"uploadId": upload_id, "nextOffset": new_offset, "status": "UPLOADING"}

    @app.post("/v1/uploads/{upload_id}/complete")
    def complete_upload(upload_id: str):
        part = storage / "uploads" / f"{upload_id}.part"
        with connect() as db:
            record = db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
            if not record:
                fail("UPLOAD_NOT_FOUND", "Upload does not exist", 404)
            if record["status"] == "COMPLETED":
                return video_payload_for(db, record["video_id"])
            if record["status"] == "FINALIZING":
                fail("UPLOAD_FINALIZING", "Upload is being verified", 409)
            if record["status"] != "UPLOADING":
                fail("UPLOAD_CLOSED", "Upload is not active", 409)
            if record["next_offset"] != record["expected_size"]:
                fail("UPLOAD_INCOMPLETE", "Upload is incomplete", 409, expectedSize=record["expected_size"], nextOffset=record["next_offset"])
            if not part.exists() or part.stat().st_size != record["expected_size"]:
                fail("UPLOAD_FILE_MISMATCH", "Stored file missing or incorrect size", 409)
            db.execute("UPDATE uploads SET status='FINALIZING' WHERE id=?", (upload_id,))

        # Hashing and probing can take long; keep them outside the write transaction.
        try:
            sha = hashlib.sha256()
            with part.open("rb") as fh:
                for block in iter(lambda: fh.read(1024 * 1024), b""):
                    sha.update(block)
            digest = sha.hexdigest()
            if record["client_sha256"] and record["client_sha256"] != digest:
                with part.open("r+b") as fh:
                    fh.truncate(0)
                with connect() as db:
                    db.execute("UPDATE uploads SET status='UPLOADING', next_offset=0 WHERE id=?", (upload_id,))
                fail("UPLOAD_CHECKSUM_MISMATCH", "Stored bytes differ from the declared SHA-256; send the file again", 422, expectedOffset=0)
            probe = media.probe_video(part)
            video_id = new_id()
            with connect() as db:
                db.execute(
                    "INSERT INTO videos(id, match_id, upload_id, size_bytes, sha256, mime_type, status, reject_code, media_json, warnings_json, probe_version) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    (video_id, record["match_id"], upload_id, record["expected_size"], digest, record["mime_type"],
                     probe.status, probe.reject_code, json.dumps(probe.media) if probe.media else None,
                     json.dumps(probe.warnings), media.PROBE_VERSION),
                )
                db.execute("UPDATE uploads SET status='COMPLETED', video_id=? WHERE id=?", (video_id, upload_id))
                payload = video_payload_for(db, video_id)
                # Last step of the transaction: a failed move rolls the rows back.
                part.replace(storage / "videos" / f"{video_id}.bin")
                return payload
        except HTTPException:
            raise
        except Exception:
            with connect() as db:
                db.execute("UPDATE uploads SET status='UPLOADING' WHERE id=? AND status='FINALIZING'", (upload_id,))
            raise

    def video_payload_for(db, video_id: str) -> dict[str, Any]:
        payload = video_payload(db.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone())
        return {**payload, "videoId": payload["id"]}

    @app.get("/v1/videos/{video_id}")
    def video_status(video_id: str):
        with connect() as db:
            record = db.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone()
            if not record:
                fail("VIDEO_NOT_FOUND", "Video does not exist", 404)
        return video_payload(record)

    @app.get("/v1/videos/{video_id}/quality")
    def video_quality(video_id: str):
        with connect() as db:
            record = db.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone()
            if not record:
                fail("VIDEO_NOT_FOUND", "Video does not exist", 404)
        return quality_payload(record)

    @app.delete("/v1/videos/{video_id}", status_code=204)
    def delete_video(video_id: str):
        with connect() as db:
            record = db.execute("SELECT * FROM videos WHERE id=?", (video_id,)).fetchone()
            if not record:
                fail("VIDEO_NOT_FOUND", "Video does not exist", 404)
            db.execute("UPDATE uploads SET video_id=NULL,status='DELETED' WHERE id=?", (record["upload_id"],))
            db.execute("DELETE FROM videos WHERE id=?", (video_id,))
            (storage / "videos" / f"{video_id}.bin").unlink(missing_ok=True)

    @app.post("/v1/videos/{video_id}/analyses")
    def request_analysis(video_id: str):
        with connect() as db:
            record = db.execute("SELECT status, reject_code FROM videos WHERE id=?", (video_id,)).fetchone()
            if not record:
                fail("VIDEO_NOT_FOUND", "Video does not exist", 404)
        if record["status"] != media.READY:
            fail("VIDEO_NOT_READY", "Video did not pass media validation and can not be analysed", 409, videoStatus=record["status"], rejectCode=record["reject_code"])
        fail("MODEL_NOT_CONFIGURED", "Volleyball computer vision pipeline is not installed. No statistics have been inferred.", 503)

    return app


app = create_app()
