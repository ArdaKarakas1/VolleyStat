"""VolleyStat local-only development API; no trained AI is connected."""
from __future__ import annotations

import hashlib
import json
import os
import re
import time
from contextlib import asynccontextmanager
from datetime import date
from pathlib import Path
from typing import Any, Literal

from fastapi import FastAPI, Header, HTTPException, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import content, jobs, media, review
from .analyzers import load_analyzer
from .analyzers.base import MODEL, Analyzer
from .db import connector, find_video, migrate, new_id
from .errors import fail

CHUNK_SIZE = 4 * 1024 * 1024
MAX_VIDEO_BYTES = 4 * 1024 * 1024 * 1024
ALLOWED_MIMES = {"video/mp4", "video/quicktime", "video/webm", "video/x-matroska"}
# A finalize that has been running longer than this is treated as abandoned
# (crashed process) and may be taken over by the next complete call.
FINALIZE_STALE_SECONDS = 15 * 60


class MatchInput(BaseModel):
    model_config = ConfigDict(str_strip_whitespace=True)

    homeTeamName: str = Field(min_length=2, max_length=80)
    awayTeamName: str = Field(min_length=2, max_length=80)
    name: str | None = Field(default=None, max_length=80)
    playedOn: str | None = Field(default=None, pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    venue: str | None = Field(default=None, max_length=80)
    # A label chosen by the user. The server never claims that a match was official.
    kind: Literal["TRAINING", "OFFICIAL"] = "TRAINING"

    @field_validator("name", "playedOn", "venue", mode="before")
    @classmethod
    def blank_is_missing(cls, value):
        return None if isinstance(value, str) and not value.strip() else value

    @field_validator("kind", mode="before")
    @classmethod
    def missing_kind_is_training(cls, value):
        return "TRAINING" if value is None else value

    @field_validator("playedOn")
    @classmethod
    def real_calendar_date(cls, value):
        if value is not None:
            date.fromisoformat(value)
        return value

    @model_validator(mode="after")
    def different_teams(self):
        if self.homeTeamName.casefold() == self.awayTeamName.casefold():
            raise ValueError("Home and away teams must be different names")
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


def match_payload(match) -> dict[str, Any]:
    return {
        "id": match["id"], "homeTeamName": match["home_team"], "awayTeamName": match["away_team"],
        "name": match["name"], "playedOn": match["played_on"], "venue": match["venue"], "kind": match["kind"],
        "status": "DRAFT",
    }


def create_app(storage_root: str | Path | None = None, analyzer: Analyzer | str | None = None,
               start_worker: bool = True) -> FastAPI:
    """Build the API over one storage folder.

    `analyzer` is an analyzer object or a registry name ("none" for no analyzer); None reads
    VOLLEYSTAT_ANALYZER. `start_worker=False` keeps the background thread off so that a test can
    step the job queue itself through `app.state.worker.run_pending()`.
    """
    # Resolved before storage is touched: an unknown analyzer name must stop the start-up.
    if analyzer is None or isinstance(analyzer, str):
        analyzer = load_analyzer(analyzer if analyzer is not None else os.environ.get("VOLLEYSTAT_ANALYZER"))
    storage = Path(storage_root or os.environ.get("VOLLEYSTAT_STORAGE") or (Path(__file__).parent.parent / "storage")).resolve()
    storage.mkdir(parents=True, exist_ok=True, mode=0o700)
    (storage / "media").mkdir(exist_ok=True, mode=0o700)
    db_path = storage / "volleystat.sqlite3"

    migrate(db_path)
    connect = connector(db_path)

    def upload_path(upload_id: str) -> Path:
        """One file per upload for its whole life: finalizing never moves it."""
        return storage / "media" / f"{upload_id}.bin"

    def video_path(record) -> Path:
        if record["storage_key"]:
            return storage / record["storage_key"]
        return storage / "videos" / f"{record['id']}.bin"  # layout of the 0.2 preview

    def video_payload(record) -> dict[str, Any]:
        return {
            "id": record["id"], "videoId": record["id"], "matchId": record["match_id"],
            "status": record["status"], "sizeBytes": record["size_bytes"], "sha256": record["sha256"],
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

    def find_upload(db, upload_id: str):
        record = db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
        if not record:
            fail("UPLOAD_NOT_FOUND", "Upload does not exist", 404)
        return record

    worker = jobs.JobWorker(connect, analyzer, video_path)

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if start_worker:
            worker.start()
        try:
            yield
        finally:
            if start_worker:
                # Joining the thread can wait for a running analyzer to notice the stop signal.
                await run_in_threadpool(worker.stop)

    app = FastAPI(title="VolleyStat Local Development API", version="0.4.0", lifespan=lifespan,
                  description="LOCAL TEST DATA ONLY. No authentication, no trained AI. Never expose to the internet.")
    app.state.worker = worker

    @app.exception_handler(StarletteHTTPException)
    async def http_error(_request: Request, exc: StarletteHTTPException):
        if isinstance(exc.detail, dict):
            detail = exc.detail
        else:
            code = {404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}.get(exc.status_code, "HTTP_ERROR")
            detail = {"code": code, "message": str(exc.detail), "details": {}}
        return JSONResponse(status_code=exc.status_code, content={"error": detail}, headers=getattr(exc, "headers", None))

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, exc: RequestValidationError):
        return JSONResponse(status_code=422, content={"error": {"code": "VALIDATION_ERROR", "message": "Invalid request", "details": {"fields": [{"location": ".".join(map(str, e["loc"])), "message": e["msg"]} for e in exc.errors()]}}})

    @app.get("/v1/health")
    def health():
        info = analyzer.info if analyzer is not None else None
        return {"status": "ok", "mode": "local-dev",
                # A mock or an unmeasured baseline is not a trained model.
                "ai": "configured" if info is not None and info.kind == MODEL else "not-configured",
                "analyzer": {"id": info.id, "version": info.version, "kind": info.kind} if info is not None else None,
                "mediaProbe": "available" if media.probe_available() else "unavailable",
                "maxVideoBytes": MAX_VIDEO_BYTES, "chunkSizeBytes": CHUNK_SIZE}

    @app.post("/v1/matches", status_code=201)
    def create_match(payload: MatchInput):
        match_id = new_id()
        with connect(write=True) as db:
            db.execute(
                "INSERT INTO matches(id, home_team, away_team, name, played_on, venue, kind) VALUES(?,?,?,?,?,?,?)",
                (match_id, payload.homeTeamName, payload.awayTeamName, payload.name, payload.playedOn, payload.venue, payload.kind),
            )
            return match_payload(db.execute("SELECT * FROM matches WHERE id=?", (match_id,)).fetchone())

    @app.get("/v1/matches/{match_id}")
    def get_match(match_id: str):
        with connect() as db:
            match = db.execute("SELECT * FROM matches WHERE id=?", (match_id,)).fetchone()
            if not match:
                fail("MATCH_NOT_FOUND", "Match does not exist", 404)
        return match_payload(match)

    @app.post("/v1/matches/{match_id}/uploads", status_code=201)
    def initiate_upload(match_id: str, payload: UploadInput):
        upload_id = new_id()
        with connect(write=True) as db:
            if not db.execute("SELECT 1 FROM matches WHERE id=?", (match_id,)).fetchone():
                fail("MATCH_NOT_FOUND", "Match does not exist", 404)
            db.execute("INSERT INTO uploads(id,match_id,file_name,mime_type,expected_size,client_sha256) VALUES(?,?,?,?,?,?)", (upload_id, match_id, payload.fileName, payload.mimeType, payload.sizeBytes, payload.clientSha256))
            upload_path(upload_id).touch(exist_ok=False)
        return {"uploadId": upload_id, "nextOffset": 0, "chunkSizeBytes": CHUNK_SIZE, "status": "UPLOADING"}

    @app.get("/v1/uploads/{upload_id}")
    def get_upload(upload_id: str):
        with connect() as db:
            record = find_upload(db, upload_id)
        return {"uploadId": record["id"], "nextOffset": record["next_offset"], "sizeBytes": record["expected_size"], "status": record["status"], "videoId": record["video_id"]}

    def store_chunk(upload_id: str, offset: int, data: bytes) -> dict[str, Any]:
        resume_from = None
        with connect(write=True) as db:
            record = find_upload(db, upload_id)
            if record["status"] != "UPLOADING":
                fail("UPLOAD_CLOSED", "Upload is not active", 409)
            part = upload_path(upload_id)
            stored = part.stat().st_size if part.exists() else 0
            if stored < record["next_offset"]:
                # Bytes the database counted are gone; continue from what is really on disk.
                part.touch()
                db.execute("UPDATE uploads SET next_offset=? WHERE id=?", (stored, upload_id))
                resume_from = stored
            elif offset != record["next_offset"]:
                fail("OFFSET_MISMATCH", "Expected a different offset", 409, expectedOffset=record["next_offset"])
            elif offset + len(data) > record["expected_size"]:
                fail("UPLOAD_OVERFLOW", "Chunk exceeds declared size", 413)
            else:
                with part.open("r+b") as fh:
                    # Bytes past the committed offset come from a write that was never
                    # committed (interrupted request); the committed offset wins.
                    fh.truncate(offset)
                    fh.seek(offset)
                    fh.write(data)
                    fh.flush()
                    os.fsync(fh.fileno())
                new_offset = offset + len(data)
                db.execute("UPDATE uploads SET next_offset=? WHERE id=?", (new_offset, upload_id))
        if resume_from is not None:
            fail("OFFSET_MISMATCH", "Stored bytes were shorter than recorded; resume from the expected offset", 409, expectedOffset=resume_from)
        return {"uploadId": upload_id, "nextOffset": new_offset, "status": "UPLOADING"}

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
        # Disk and database work must not block the event loop.
        return await run_in_threadpool(store_chunk, upload_id, offset, bytes(data))

    @app.post("/v1/uploads/{upload_id}/complete")
    def complete_upload(upload_id: str):
        part = upload_path(upload_id)
        started = time.time()
        resume_from = None
        with connect(write=True) as db:
            record = find_upload(db, upload_id)
            if record["status"] == "COMPLETED":
                return video_payload(find_video(db, record["video_id"]))
            if record["status"] == "FINALIZING":
                if started - (record["finalize_started_at"] or 0) < FINALIZE_STALE_SECONDS:
                    fail("UPLOAD_FINALIZING", "Upload is being verified", 409)
            elif record["status"] != "UPLOADING":
                fail("UPLOAD_CLOSED", "Upload is not active", 409)
            stored = part.stat().st_size if part.exists() else 0
            if stored < record["next_offset"]:
                part.touch()
                db.execute("UPDATE uploads SET next_offset=?, status='UPLOADING', finalize_started_at=NULL WHERE id=?", (stored, upload_id))
                resume_from = stored
            elif record["next_offset"] != record["expected_size"]:
                fail("UPLOAD_INCOMPLETE", "Upload is incomplete", 409, expectedSize=record["expected_size"], nextOffset=record["next_offset"])
            else:
                if stored > record["expected_size"]:
                    with part.open("r+b") as fh:
                        fh.truncate(record["expected_size"])
                db.execute("UPDATE uploads SET status='FINALIZING', finalize_started_at=? WHERE id=?", (started, upload_id))
        if resume_from is not None:
            fail("UPLOAD_INCOMPLETE", "Stored bytes were shorter than recorded; resume the upload", 409, expectedSize=record["expected_size"], nextOffset=resume_from)

        def still_ours(db) -> bool:
            row = db.execute("SELECT status, finalize_started_at FROM uploads WHERE id=?", (upload_id,)).fetchone()
            return bool(row) and row["status"] == "FINALIZING" and row["finalize_started_at"] == started

        # Hashing and probing can take long; keep them outside the write transaction.
        try:
            sha = hashlib.sha256()
            with part.open("rb") as fh:
                for block in iter(lambda: fh.read(1024 * 1024), b""):
                    sha.update(block)
            digest = sha.hexdigest()
            if record["client_sha256"] and record["client_sha256"] != digest:
                with connect(write=True) as db:
                    if still_ours(db):
                        # Reset the row first: if the truncate below is interrupted, the next
                        # chunk at offset 0 truncates the leftover bytes anyway.
                        db.execute("UPDATE uploads SET status='UPLOADING', next_offset=0, finalize_started_at=NULL WHERE id=?", (upload_id,))
                with part.open("r+b") as fh:
                    fh.truncate(0)
                fail("UPLOAD_CHECKSUM_MISMATCH", "Stored bytes differ from the declared SHA-256; send the file again", 422, expectedOffset=0)
            probe = media.probe_video(part, record["mime_type"])
            video_id = new_id()
            with connect(write=True) as db:
                if not still_ours(db):
                    fail("UPLOAD_FINALIZING", "Another request took over verification of this upload", 409)
                # The file is not moved, so this commit is the whole finalize.
                db.execute(
                    "INSERT INTO videos(id, match_id, upload_id, size_bytes, sha256, mime_type, status, reject_code, media_json, warnings_json, probe_version, storage_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    (video_id, record["match_id"], upload_id, record["expected_size"], digest, record["mime_type"],
                     probe.status, probe.reject_code, json.dumps(probe.media) if probe.media else None,
                     json.dumps(probe.warnings), media.PROBE_VERSION, f"media/{upload_id}.bin"),
                )
                db.execute("UPDATE uploads SET status='COMPLETED', video_id=?, finalize_started_at=NULL WHERE id=?", (video_id, upload_id))
                return video_payload(find_video(db, video_id))
        except HTTPException:
            raise
        except Exception:
            with connect(write=True) as db:
                if still_ours(db):
                    db.execute("UPDATE uploads SET status='UPLOADING', finalize_started_at=NULL WHERE id=?", (upload_id,))
            raise

    @app.delete("/v1/uploads/{upload_id}", status_code=204)
    def abort_upload(upload_id: str):
        """Give up an unfinished upload and free its bytes."""
        with connect(write=True) as db:
            record = find_upload(db, upload_id)
            if record["status"] == "COMPLETED":
                fail("UPLOAD_COMPLETED", "Upload already became a video; delete the video instead", 409, videoId=record["video_id"])
            if record["status"] in ("ABORTED", "DELETED"):
                return
            if record["status"] == "FINALIZING" and time.time() - (record["finalize_started_at"] or 0) < FINALIZE_STALE_SECONDS:
                fail("UPLOAD_FINALIZING", "Upload is being verified", 409)
            upload_path(upload_id).unlink(missing_ok=True)
            db.execute("UPDATE uploads SET status='ABORTED', finalize_started_at=NULL WHERE id=?", (upload_id,))

    @app.get("/v1/videos/{video_id}")
    def video_status(video_id: str):
        with connect() as db:
            return video_payload(find_video(db, video_id))

    @app.get("/v1/videos/{video_id}/quality")
    def video_quality(video_id: str):
        with connect() as db:
            return quality_payload(find_video(db, video_id))

    @app.post("/v1/videos/{video_id}/quality")
    def recheck_quality(video_id: str):
        """Run the file check again for a video that could not be validated earlier."""
        with connect() as db:
            record = find_video(db, video_id)
        if record["status"] != media.STORED_UNVALIDATED:
            return quality_payload(record)
        path = video_path(record)
        if not path.is_file():
            fail("VIDEO_FILE_MISSING", "Stored bytes for this video are missing", 409)
        probe = media.probe_video(path, record["mime_type"])
        with connect(write=True) as db:
            find_video(db, video_id)
            db.execute(
                "UPDATE videos SET status=?, reject_code=?, media_json=?, warnings_json=?, probe_version=? WHERE id=? AND status=?",
                (probe.status, probe.reject_code, json.dumps(probe.media) if probe.media else None,
                 json.dumps(probe.warnings), media.PROBE_VERSION, video_id, media.STORED_UNVALIDATED),
            )
            return quality_payload(find_video(db, video_id))

    @app.get("/v1/videos/{video_id}/content", response_class=StreamingResponse, responses={
        200: {"description": "The whole video", "content": {"video/*": {}}},
        206: {"description": "The requested byte range", "content": {"video/*": {}}},
    })
    def video_content(video_id: str, range_header: str | None = Header(default=None, alias="Range"),
                      if_range: str | None = Header(default=None, alias="If-Range")):
        """Evidence playback: the client plays the clip of a review card from these bytes."""
        with connect() as db:
            record = find_video(db, video_id)
        if record["status"] != media.READY:
            fail("VIDEO_NOT_READY", "Only a video that passed media validation can be played", 409, videoStatus=record["status"], rejectCode=record["reject_code"])
        try:
            handle = video_path(record).open("rb")
        except FileNotFoundError:
            fail("VIDEO_FILE_MISSING", "Stored bytes for this video are missing", 409)
        return content.stream_file(handle, os.fstat(handle.fileno()).st_size, json.loads(record["media_json"])["container"], range_header, if_range)

    @app.delete("/v1/videos/{video_id}", status_code=204)
    def delete_video(video_id: str):
        with connect(write=True) as db:
            record = find_video(db, video_id)
            pending = [row["id"] for row in db.execute(
                "SELECT id FROM analysis_jobs WHERE video_id=? AND state IN (?,?)", (video_id, *jobs.ACTIVE_STATES))]
            db.execute("UPDATE uploads SET video_id=NULL,status='DELETED' WHERE id=?", (record["upload_id"],))
            # The jobs of the video and their rallies, cards and decisions go with it (ON DELETE CASCADE).
            db.execute("DELETE FROM videos WHERE id=?", (video_id,))
            # Remove the bytes before the commit: a deletion must never leave the file behind.
            video_path(record).unlink(missing_ok=True)
        # A run in progress has lost its job row and can write nothing; this only makes it stop sooner.
        for job_id in pending:
            worker.release(job_id)

    app.include_router(jobs.router(connect, analyzer, worker))
    app.include_router(review.router(connect))
    return app
