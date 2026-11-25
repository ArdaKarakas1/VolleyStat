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

from fastapi import Depends, FastAPI, Header, Request, Response
from fastapi.concurrency import run_in_threadpool
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator
from starlette.exceptions import HTTPException as StarletteHTTPException

from . import access, accounts, content, corrections, deletion, jobs, media, quotas, report, review, sweep
from .access import READ, REVIEW
from .analyzers import load_analyzer, require_known_kind
from .analyzers.base import MODEL, Analyzer
from .bodylimit import BodyLimit
from .db import connector, empty_write_ahead_log, find_video, is_canonical_id, migrate, new_id
from .deletion import FINALIZE_STALE_SECONDS
from .errors import fail

CHUNK_SIZE = 4 * 1024 * 1024
CHUNK_ROUTE = re.compile(r"/v1/uploads/[^/]+/chunks")
MAX_VIDEO_BYTES = 4 * 1024 * 1024 * 1024
ALLOWED_MIMES = {"video/mp4", "video/quicktime", "video/webm", "video/x-matroska"}
VERSION = "0.9.0"


class MatchRulesInput(BaseModel):
    """The rule the user chose: the app offers exactly these values, so the server accepts nothing else."""
    model_config = ConfigDict(extra="forbid")

    # The enum is for the OpenAPI document. The validators below are what refuses another value.
    setsToWin: int = Field(strict=True, json_schema_extra={"enum": list(report.SETS_TO_WIN)})
    setTarget: int = Field(strict=True, json_schema_extra={"enum": list(report.TARGETS)})
    decidingSetTarget: int = Field(strict=True, json_schema_extra={"enum": list(report.TARGETS)})

    @field_validator("setsToWin")
    @classmethod
    def offered_sets_to_win(cls, value):
        if value not in report.SETS_TO_WIN:
            raise ValueError(f"setsToWin must be one of {report.SETS_TO_WIN}")
        return value

    @field_validator("setTarget", "decidingSetTarget")
    @classmethod
    def offered_target(cls, value):
        if value not in report.TARGETS:
            raise ValueError(f"A set target must be one of {report.TARGETS}")
        return value

    def rules(self) -> report.MatchRules:
        return report.MatchRules(self.setsToWin, self.setTarget, self.decidingSetTarget)


class MatchRulesChange(MatchRulesInput):
    """A rule change. It also names the rule its sender last read. While the match has another rule,
    a request the phone gave up on is refused, so it does not undo a later change to another rule.
    The check compares rules: when later changes brought the match back to the rule the request
    names, the late request still lands (ADR-0115)."""

    expectedRules: MatchRulesInput


class MatchInput(BaseModel):
    # An unknown key is refused: a rule written beside "rules" or under another name would otherwise be
    # dropped in silence, and the idempotent PUT would then keep the FIVB rule for good.
    model_config = ConfigDict(str_strip_whitespace=True, extra="forbid")

    homeTeamName: str = Field(min_length=2, max_length=80)
    awayTeamName: str = Field(min_length=2, max_length=80)
    name: str | None = Field(default=None, max_length=80)
    playedOn: str | None = Field(default=None, pattern=r"^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
    venue: str | None = Field(default=None, max_length=80)
    # A label chosen by the user. The server never claims that a match was official.
    kind: Literal["TRAINING", "OFFICIAL"] = "TRAINING"
    # Missing or null: the FIVB rule, which is how every match was counted before the user could choose.
    rules: MatchRulesInput | None = None

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


def is_chunk_upload(scope) -> bool:
    """The one request whose body is not JSON. Its route streams the body and enforces CHUNK_SIZE itself."""
    return scope["method"] == "PUT" and CHUNK_ROUTE.fullmatch(scope["path"]) is not None


def match_payload(match) -> dict[str, Any]:
    return {
        "id": match["id"], "homeTeamName": match["home_team"], "awayTeamName": match["away_team"],
        "name": match["name"], "playedOn": match["played_on"], "venue": match["venue"], "kind": match["kind"],
        "rules": report.MatchRules.of(match).payload(),
        # PERSONAL for every match a caller can read in this version.
        "status": match["status"],
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
    if analyzer is not None:
        require_known_kind(analyzer)
    # An unknown gate setting or disk floor must stop the start-up too, before storage is touched.
    signup_mode, team_mode = accounts.settings()
    floor = quotas.settings()
    storage = Path(storage_root or os.environ.get("VOLLEYSTAT_STORAGE") or (Path(__file__).parent.parent / "storage")).resolve()
    storage.mkdir(parents=True, exist_ok=True, mode=0o700)
    (storage / "media").mkdir(exist_ok=True, mode=0o700)
    db_path = storage / "volleystat.sqlite3"

    migrate(db_path)
    connect = connector(db_path)
    files = deletion.MediaFiles(storage)
    upload_path, video_path = files.upload, files.video
    # Every route but health, register and login declares it (docs/API_V1.md, section 10).
    current_user = accounts.current_user_dependency(connect)

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
    sweeper = sweep.Sweeper(connect, files)
    hash_pool = accounts.HashPool()

    @asynccontextmanager
    async def lifespan(_app: FastAPI):
        if start_worker:
            worker.start()
            sweeper.start()
        try:
            yield
        finally:
            hash_pool.close()
            if start_worker:
                # Joining the thread can wait for a running analyzer to notice the stop signal.
                await run_in_threadpool(worker.stop)
                await run_in_threadpool(sweeper.stop)

    # No /docs, /redoc or /openapi.json: they would answer without a session, and /docs loads its
    # scripts from a CDN. app.openapi() still builds the committed document (docs/openapi-local-dev.json).
    app = FastAPI(title="VolleyStat Local Development API", version=VERSION, lifespan=lifespan,
                  docs_url=None, redoc_url=None, openapi_url=None,
                  description="LOCAL TEST DATA ONLY. Every route except health, register and login needs a session."
                              " No trained AI. Never expose to the internet.")
    app.state.worker = worker
    app.state.sweeper = sweeper
    app.state.hash_pool = hash_pool
    app.add_middleware(BodyLimit, exempt=is_chunk_upload)

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
                "maxVideoBytes": MAX_VIDEO_BYTES, "chunkSizeBytes": CHUNK_SIZE,
                "accounts": {"signup": signup_mode, "teams": team_mode}}

    def insert_match(db, match_id: str, payload: MatchInput, caller: accounts.Caller):
        """A personal match of the caller (design 5.6): only that account sees it."""
        rules = payload.rules.rules() if payload.rules is not None else report.FIVB
        db.execute(
            "INSERT INTO matches(id, home_team, away_team, name, played_on, venue, kind, sets_to_win, set_target,"
            " deciding_set_target, status, owner_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,'PERSONAL',?)",
            (match_id, payload.homeTeamName, payload.awayTeamName, payload.name, payload.playedOn, payload.venue, payload.kind,
             rules.sets_to_win, rules.set_target, rules.deciding_set_target, caller.id),
        )
        return db.execute("SELECT * FROM matches WHERE id=?", (match_id,)).fetchone()

    @app.post("/v1/matches", status_code=201)
    def create_match(payload: MatchInput, caller: accounts.Caller = Depends(current_user)):
        with connect(write=True) as db:
            access.session_user(db, caller)
            return match_payload(insert_match(db, new_id(), payload, caller))

    @app.put("/v1/matches/{match_id}", responses={201: {"description": "The match was created under this id"}})
    def put_match(match_id: str, payload: MatchInput, response: Response,
                  caller: accounts.Caller = Depends(current_user)):
        """Create the match under an id the client chose, or return the match that already has it.

        A client that picks the id before its first request can address the match whatever happens
        to the answer: it repeats this call, or deletes the match, with the id it already holds.
        With POST the id exists only in the answer, and a lost answer leaves a match nobody can
        delete. A repeat never changes what the first request stored, and only an account that may
        read the match gets it back; any other account gets 403 NOT_A_MEMBER.
        """
        if not is_canonical_id(match_id):
            fail("MATCH_ID_INVALID", "Match id must be a UUID in lowercase with dashes", 422)
        with connect(write=True) as db:
            access.session_user(db, caller)
            if db.execute("SELECT 1 FROM matches WHERE id=?", (match_id,)).fetchone() is None:
                response.status_code = 201
                return match_payload(insert_match(db, match_id, payload, caller))
            return match_payload(access.require(db, caller, "match", match_id, READ))

    @app.put("/v1/matches/{match_id}/rules")
    def put_match_rules(match_id: str, payload: MatchRulesChange, caller: accounts.Caller = Depends(current_user)):
        """Set the rule the server counts the sets of this match with.

        A rule that differs from the stored one moves the rally list of every analysis of the match
        that has rallies one version on, in the same transaction. The user confirmed those lists
        while looking at set scores of the old rule, so each confirmation lapses, and a confirmation
        or a correction still on its way with the old version is refused as stale. Answers, cards and
        corrections stay as they are.

        The rule the match already has changes nothing, so a repeated request lands once. Any other
        request must name the rule the match has now (expectedRules). A request that names another rule
        was sent before a later change, and it is refused as stale instead of undoing that change.
        Only the rules are compared: a late request lands when the match is back at the rule it names.
        """
        with connect(write=True) as db:
            access.session_user(db, caller)
            # Before the rule check: a stranger must not learn the current rule from MATCH_RULES_STALE.
            match = access.require(db, caller, "match", match_id, REVIEW)
            stored, wanted = report.MatchRules.of(match), payload.rules()
            if stored != wanted:
                if stored != payload.expectedRules.rules():
                    fail("MATCH_RULES_STALE", "The rule of the match changed since it was read", 409,
                         currentRules=stored.payload())
                db.execute("UPDATE matches SET sets_to_win=?, set_target=?, deciding_set_target=? WHERE id=?",
                           (wanted.sets_to_win, wanted.set_target, wanted.deciding_set_target, match_id))
                db.execute(
                    "UPDATE analysis_jobs SET rally_list_version=rally_list_version+1, revision=revision+1"
                    " WHERE match_id=? AND state IN (?,?)", (match_id, jobs.NEEDS_REVIEW, jobs.COMPLETE))
                match = db.execute("SELECT * FROM matches WHERE id=?", (match_id,)).fetchone()
            return match_payload(match)

    @app.get("/v1/matches/{match_id}")
    def get_match(match_id: str, caller: accounts.Caller = Depends(current_user)):
        with connect() as db:
            match = access.require(db, caller, "match", match_id, READ)
        return match_payload(match)

    @app.post("/v1/matches/{match_id}/uploads", status_code=201, responses={
        200: {"description": "This account already had an unfinished upload of the same file for this match;"
                             " the answer names it and the offset to continue from"}})
    def initiate_upload(match_id: str, payload: UploadInput, response: Response,
                        caller: accounts.Caller = Depends(current_user)):
        """Start an upload of a video of the match. The quotas, the server-wide pool and the free-disk
        floor are checked in the transaction that inserts the row (docs/API_V1.md, section 10).

        A start the account repeats for the same file (same match, checksum, size, name and type) gets
        the unfinished upload it already has, before any limit is checked: a phone that lost the first
        answer never learned that upload's id, and a second row would hold the same bytes twice."""
        upload_id = new_id()
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require(db, caller, "match", match_id, REVIEW)
            if payload.clientSha256 is not None:
                same = db.execute(
                    "SELECT id, next_offset FROM uploads WHERE match_id=? AND started_by=? AND status='UPLOADING'"
                    " AND client_sha256=? AND expected_size=? AND file_name=? AND mime_type=? ORDER BY rowid LIMIT 1",
                    (match_id, caller.id, payload.clientSha256, payload.sizeBytes, payload.fileName,
                     payload.mimeType)).fetchone()
                if same is not None:
                    # A start is activity: the sweep waits a day of server uptime from it, as from a new upload.
                    db.execute("UPDATE uploads SET touched_at=? WHERE id=?", (time.time(), same["id"]))
                    response.status_code = 200
                    return {"uploadId": same["id"], "nextOffset": same["next_offset"], "chunkSizeBytes": CHUNK_SIZE,
                            "status": "UPLOADING"}
            quotas.check_upload(db, caller, payload.sizeBytes, storage, floor)
            db.execute("INSERT INTO uploads(id,match_id,file_name,mime_type,expected_size,client_sha256,started_by,touched_at)"
                       " VALUES(?,?,?,?,?,?,?,?)",
                       (upload_id, match_id, payload.fileName, payload.mimeType, payload.sizeBytes, payload.clientSha256,
                        caller.id, time.time()))
            upload_path(upload_id).touch(exist_ok=False)
        return {"uploadId": upload_id, "nextOffset": 0, "chunkSizeBytes": CHUNK_SIZE, "status": "UPLOADING"}

    @app.get("/v1/uploads/{upload_id}")
    def get_upload(upload_id: str, caller: accounts.Caller = Depends(current_user)):
        with connect() as db:
            # The status of an upload belongs to its reviewer (design 6.1).
            access.require(db, caller, "upload", upload_id, REVIEW)
            record = find_upload(db, upload_id)
        return {"uploadId": record["id"], "nextOffset": record["next_offset"], "sizeBytes": record["expected_size"], "status": record["status"], "videoId": record["video_id"]}

    def store_chunk(upload_id: str, offset: int, data: bytes, caller: accounts.Caller) -> dict[str, Any]:
        resume_from = None
        with connect(write=True) as db:
            access.session_user(db, caller)
            # Before the state and offset checks: a stranger must not learn the expected offset.
            access.require(db, caller, "upload", upload_id, REVIEW)
            record = find_upload(db, upload_id)
            if record["status"] != "UPLOADING":
                fail("UPLOAD_CLOSED", "Upload is not active", 409)
            part = upload_path(upload_id)
            stored = part.stat().st_size if part.exists() else 0
            if stored < record["next_offset"]:
                # Bytes the database counted are gone; continue from what is really on disk.
                part.touch()
                db.execute("UPDATE uploads SET next_offset=?, touched_at=? WHERE id=?", (stored, time.time(), upload_id))
                resume_from = stored
            elif offset != record["next_offset"]:
                fail("OFFSET_MISMATCH", "Expected a different offset", 409, expectedOffset=record["next_offset"])
            elif offset + len(data) > record["expected_size"]:
                fail("UPLOAD_OVERFLOW", "Chunk exceeds declared size", 413)
            else:
                # Nothing is written, and the offset stays, when the chunk would fill the disk.
                quotas.check_chunk(storage, len(data), floor)
                with part.open("r+b") as fh:
                    # Bytes past the committed offset come from a write that was never
                    # committed (interrupted request); the committed offset wins.
                    fh.truncate(offset)
                    fh.seek(offset)
                    fh.write(data)
                    fh.flush()
                    os.fsync(fh.fileno())
                new_offset = offset + len(data)
                # touched_at moves with every stored chunk: the sweep closes an upload a day of uptime after the last one.
                db.execute("UPDATE uploads SET next_offset=?, touched_at=? WHERE id=?", (new_offset, time.time(), upload_id))
        if resume_from is not None:
            fail("OFFSET_MISMATCH", "Stored bytes were shorter than recorded; resume from the expected offset", 409, expectedOffset=resume_from)
        return {"uploadId": upload_id, "nextOffset": new_offset, "status": "UPLOADING"}

    @app.put("/v1/uploads/{upload_id}/chunks")
    async def upload_chunk(upload_id: str, request: Request, offset: int,
                           caller: accounts.Caller = Depends(current_user)):
        # The session was read before the body: a request without one sends its bytes to nobody.
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
        return await run_in_threadpool(store_chunk, upload_id, offset, bytes(data), caller)

    @app.post("/v1/uploads/{upload_id}/complete")
    def complete_upload(upload_id: str, caller: accounts.Caller = Depends(current_user)):
        part = upload_path(upload_id)
        started = time.time()
        resume_from = None
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require(db, caller, "upload", upload_id, REVIEW)
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
                db.execute("UPDATE uploads SET next_offset=?, status='UPLOADING', finalize_started_at=NULL, touched_at=? WHERE id=?",
                           (stored, time.time(), upload_id))
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
            # A sweep pass that moved finalize_started_at back to its own moment (the clock went back while
            # this verification ran, see sweep._CLAMP) ends the ownership as a takeover does: the request
            # answers 409 and the next complete verifies the upload again 15 minutes after that pass.
            row = db.execute("SELECT status, finalize_started_at FROM uploads WHERE id=?", (upload_id,)).fetchone()
            return bool(row) and row["status"] == "FINALIZING" and row["finalize_started_at"] == started

        # Hashing and probing can take long; keep them outside the write transaction. The two
        # transactions that only undo this route's own FINALIZING mark (the checksum reset and the
        # reopen below) do not read the session: a 401 there would replace the real answer and leave
        # the upload FINALIZING, where it holds its quota until a later call takes it over.
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
                        db.execute("UPDATE uploads SET status='UPLOADING', next_offset=0, finalize_started_at=NULL, touched_at=? WHERE id=?",
                                   (time.time(), upload_id))
                with part.open("r+b") as fh:
                    fh.truncate(0)
                fail("UPLOAD_CHECKSUM_MISMATCH", "Stored bytes differ from the declared SHA-256; send the file again", 422, expectedOffset=0)
            probe = media.probe_video(part, record["mime_type"])
            video_id = new_id()
            with connect(write=True) as db:
                # The session may have ended while the file was checked: then no video is stored.
                access.session_user(db, caller)
                access.require(db, caller, "upload", upload_id, REVIEW)
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
        except Exception:
            # Any failure, a refusal of the final transaction included, gives the upload back as it
            # was. still_ours keeps this from touching a row that another request took over or that
            # the checksum reset already reopened.
            with connect(write=True) as db:
                if still_ours(db):
                    db.execute("UPDATE uploads SET status='UPLOADING', finalize_started_at=NULL, touched_at=? WHERE id=?",
                               (time.time(), upload_id))
            raise

    @app.delete("/v1/uploads/{upload_id}", status_code=204)
    def abort_upload(upload_id: str, caller: accounts.Caller = Depends(current_user)):
        """Give up an unfinished upload and free its bytes."""
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require(db, caller, "upload", upload_id, REVIEW)
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
    def video_status(video_id: str, caller: accounts.Caller = Depends(current_user)):
        with connect() as db:
            access.require(db, caller, "video", video_id, READ)
            return video_payload(find_video(db, video_id))

    @app.get("/v1/videos/{video_id}/quality")
    def video_quality(video_id: str, caller: accounts.Caller = Depends(current_user)):
        with connect() as db:
            access.require(db, caller, "video", video_id, READ)
            return quality_payload(find_video(db, video_id))

    @app.post("/v1/videos/{video_id}/quality")
    def recheck_quality(video_id: str, caller: accounts.Caller = Depends(current_user)):
        """Run the file check again for a video that could not be validated earlier."""
        with connect() as db:
            # Before the early answer below, which shows the stored check.
            access.require(db, caller, "video", video_id, REVIEW)
            record = find_video(db, video_id)
        if record["status"] != media.STORED_UNVALIDATED:
            return quality_payload(record)
        path = video_path(record)
        if not path.is_file():
            fail("VIDEO_FILE_MISSING", "Stored bytes for this video are missing", 409)
        probe = media.probe_video(path, record["mime_type"])
        with connect(write=True) as db:
            # The probe ran outside a transaction: the session and the access are read again.
            access.session_user(db, caller)
            access.require(db, caller, "video", video_id, REVIEW)
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
                      if_range: str | None = Header(default=None, alias="If-Range"),
                      caller: accounts.Caller = Depends(current_user)):
        """Evidence playback: the client plays the clip of a review card from these bytes.

        The player sends the session in the Authorization header too; no address carries a token.
        """
        with connect() as db:
            # Before VIDEO_NOT_READY, which names the video's status, and before the file opens.
            access.require(db, caller, "video", video_id, READ)
            record = find_video(db, video_id)
        if record["status"] != media.READY:
            fail("VIDEO_NOT_READY", "Only a video that passed media validation can be played", 409, videoStatus=record["status"], rejectCode=record["reject_code"])
        try:
            handle = video_path(record).open("rb")
        except FileNotFoundError:
            fail("VIDEO_FILE_MISSING", "Stored bytes for this video are missing", 409)
        return content.stream_file(handle, os.fstat(handle.fileno()).st_size, json.loads(record["media_json"])["container"], range_header, if_range)

    @app.delete("/v1/videos/{video_id}", status_code=204)
    def delete_video(video_id: str, caller: accounts.Caller = Depends(current_user)):
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require(db, caller, "video", video_id, REVIEW)
            record = find_video(db, video_id)
            pending = [row["id"] for row in db.execute(
                "SELECT id FROM analysis_jobs WHERE video_id=? AND state IN (?,?)", (video_id, *jobs.ACTIVE_STATES))]
            db.execute("UPDATE uploads SET video_id=NULL,status='DELETED' WHERE id=?", (record["upload_id"],))
            # The jobs of the video and their rallies, cards and decisions go with it (ON DELETE CASCADE).
            db.execute("DELETE FROM videos WHERE id=?", (video_id,))
            # Remove the bytes before the commit: a deletion must never leave the file behind.
            video_path(record).unlink(missing_ok=True)
        empty_write_ahead_log(db_path)
        # A run in progress has lost its job row and can write nothing; this only makes it stop sooner.
        for job_id in pending:
            worker.release(job_id)

    @app.delete("/v1/matches/{match_id}", status_code=204)
    def delete_match(match_id: str, caller: accounts.Caller = Depends(current_user)):
        """Remove a match and everything the server holds for it: its record, uploads, videos, analyses and bytes.

        Deleting a video leaves the match record and the upload rows behind. This is the call a
        client makes when the user removes the match itself, so that nothing of it stays. A match
        that belongs to no account (LEGACY) answers 403; the operator deletes it (`legacy --delete`).
        """
        with connect(write=True) as db:
            access.session_user(db, caller)
            access.require(db, caller, "match", match_id, REVIEW)
            # A verification in progress is about to turn its upload into a video of this match.
            # The route refuses before it removes a row or a file.
            if deletion.finalizing(db, match_id, time.time()):
                fail("UPLOAD_FINALIZING", "An upload of this match is being verified", 409)
            removed = deletion.delete_match_rows(db, files, match_id)
            # Remove the bytes before the commit: a deletion must never leave a file behind. If the
            # commit then fails, the rows return without their bytes and the same call clears them.
            removed.unlink()
        empty_write_ahead_log(db_path)
        # A run in progress has lost its job row and can write nothing; this only makes it stop sooner.
        for job_id in removed.pending_job_ids:
            worker.release(job_id)

    app.include_router(jobs.router(connect, analyzer, worker, current_user))
    app.include_router(review.router(connect, current_user))
    app.include_router(corrections.router(connect, current_user))
    app.include_router(accounts.router(connect, db_path, signup_mode, accounts.Limiter(), hash_pool,
                                       current_user, files, worker.release))
    return app
