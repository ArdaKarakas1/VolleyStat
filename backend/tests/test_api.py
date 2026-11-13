import asyncio
import hashlib
import json
import sqlite3
import time

import pytest

import app.main as main_module
from app.bodylimit import MAX_JSON_BODY_BYTES, BodyLimit
from app.main import CHUNK_SIZE


def create_match(c):
    resp = c.post("/v1/matches", json={"homeTeamName": "Takım A", "awayTeamName": "Takım B"})
    assert resp.status_code == 201
    return resp.json()["id"]


def create_upload(c, match_id, size=5, **overrides):
    body = {"fileName": "match.mp4", "sizeBytes": size, "mimeType": "video/mp4", "rightsAttested": True, "allowTraining": False}
    body.update(overrides)
    return c.post(f"/v1/matches/{match_id}/uploads", json=body)


def start_upload(c, size=5, **overrides):
    response = create_upload(c, create_match(c), size=size, **overrides)
    assert response.status_code == 201, response.text
    return response.json()["uploadId"]


def set_upload(storage, upload_id, **columns):
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        assignments = ", ".join(f"{name}=?" for name in columns)
        db.execute(f"UPDATE uploads SET {assignments} WHERE id=?", (*columns.values(), upload_id))


def test_health(client):
    body = client.get("/v1/health").json()
    assert body["ai"] == "not-configured"
    assert body["mediaProbe"] in ("available", "unavailable")
    assert body["maxVideoBytes"] == 4 * 1024 ** 3 and body["chunkSizeBytes"] == CHUNK_SIZE


def test_importing_the_module_creates_no_app_and_no_storage():
    # A second process importing the module (tests, tooling) must not touch live storage.
    assert not hasattr(main_module, "app")


def test_errors_outside_the_routes_use_the_error_envelope(client):
    missing = client.get("/v1/nope")
    assert missing.status_code == 404
    assert missing.json()["error"]["code"] == "NOT_FOUND"
    wrong_method = client.post("/v1/health")
    assert wrong_method.status_code == 405
    assert wrong_method.json()["error"]["code"] == "METHOD_NOT_ALLOWED"


def padded_match(size):
    """A valid match body of exactly `size` bytes; JSON allows whitespace between its tokens."""
    body = json.dumps({"homeTeamName": "Takım A", "awayTeamName": "Takım B"}).encode()
    return body[:-1] + b" " * (size - len(body)) + b"}"


def post_json(client, url, content):
    return client.post(url, content=content, headers={"Content-Type": "application/json"})


def too_large(response):
    assert response.status_code == 413, response.text
    assert response.json() == {"error": {"code": "PAYLOAD_TOO_LARGE", "message": response.json()["error"]["message"],
                                         "details": {"maxBytes": MAX_JSON_BODY_BYTES}}}
    return True


def test_a_json_body_of_64_kib_is_accepted_and_one_byte_more_is_refused(client, storage):
    assert MAX_JSON_BODY_BYTES == 64 * 1024
    assert post_json(client, "/v1/matches", padded_match(MAX_JSON_BODY_BYTES)).status_code == 201
    assert too_large(post_json(client, "/v1/matches", padded_match(MAX_JSON_BODY_BYTES + 1)))
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        assert db.execute("SELECT COUNT(*) FROM matches").fetchone()[0] == 1


def in_pieces(data, size=8192):
    """A body that httpx sends with chunked transfer encoding, that is without a Content-Length header."""
    for start in range(0, len(data), size):
        yield data[start:start + size]


def test_a_json_body_without_a_content_length_has_the_same_limit(client):
    accepted = post_json(client, "/v1/matches", in_pieces(padded_match(MAX_JSON_BODY_BYTES)))
    assert "content-length" not in accepted.request.headers
    assert accepted.status_code == 201
    refused = post_json(client, "/v1/matches", in_pieces(padded_match(MAX_JSON_BODY_BYTES + 1)))
    assert "content-length" not in refused.request.headers
    assert too_large(refused)


@pytest.mark.parametrize("method, url", [
    ("POST", "/v1/matches"),
    ("POST", "/v1/matches/unknown/uploads"),
    ("POST", "/v1/review-cards/unknown/decisions"),
    ("POST", "/v1/review-cards/unknown/reopen"),
])
def test_an_oversized_body_is_refused_before_the_route_looks_at_it(client, method, url):
    # Neither JSON nor a known id: without the limit these would answer 422 or 404 after reading it all.
    oversized = b"x" * (MAX_JSON_BODY_BYTES + 1)
    assert too_large(client.request(method, url, content=oversized))
    assert too_large(client.request(method, url, content=in_pieces(oversized)))


def test_the_chunk_route_keeps_its_own_larger_limit(client):
    size = MAX_JSON_BODY_BYTES + 1
    uid = start_upload(client, size=size)
    stored = client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"z" * size)
    assert stored.status_code == 200 and stored.json()["nextOffset"] == size


def test_the_body_limit_stops_reading_a_body_that_names_no_length():
    reached = []
    pulled = []
    sent = []

    async def route(scope, receive, send):
        reached.append(scope["path"])

    async def receive():
        pulled.append(1)
        return {"type": "http.request", "body": b"x" * 16384, "more_body": True}

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "method": "POST", "path": "/v1/matches", "headers": []}
    asyncio.run(BodyLimit(route)(scope, receive, send))
    # An endless body is given up right after the limit, and the route never sees the request.
    assert len(pulled) == MAX_JSON_BODY_BYTES // 16384 + 1
    assert reached == []
    assert sent[0]["type"] == "http.response.start" and sent[0]["status"] == 413
    assert json.loads(sent[1]["body"])["error"]["code"] == "PAYLOAD_TOO_LARGE"


def test_match_validation(client):
    assert client.post("/v1/matches", json={"homeTeamName": "A", "awayTeamName": "B"}).status_code == 422
    assert client.post("/v1/matches", json={"homeTeamName": "A Team", "awayTeamName": "a team"}).status_code == 422
    # Padding does not make a one-letter name long enough, and stored names are trimmed.
    assert client.post("/v1/matches", json={"homeTeamName": "a ", "awayTeamName": "Takım B"}).status_code == 422
    assert client.post("/v1/matches", json={"homeTeamName": "Takım A", "awayTeamName": " takım a "}).status_code == 422
    created = client.post("/v1/matches", json={"homeTeamName": "  Takım A ", "awayTeamName": "Takım B"})
    assert created.status_code == 201 and created.json()["homeTeamName"] == "Takım A"


def test_upload_rights_training_and_path(client):
    m = create_match(client)
    assert create_upload(client, m, rightsAttested=False).status_code == 422
    assert create_upload(client, m, allowTraining=True).status_code == 422
    assert create_upload(client, m, fileName="../secret.mp4").status_code == 422
    assert create_upload(client, m, mimeType="image/png").status_code == 422
    assert create_upload(client, m, clientSha256="not-a-hash").status_code == 422
    assert create_upload(client, m, size=4 * 1024 ** 3 + 1).status_code == 422
    assert create_upload(client, "not-a-match").status_code == 404


def test_upload_chunk_end_to_end_and_replay(client):
    uid = start_upload(client)
    assert client.post(f"/v1/uploads/{uid}/complete").status_code == 409
    r = client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abc")
    assert r.json()["nextOffset"] == 3
    mismatch = client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abc")
    assert mismatch.status_code == 409
    assert mismatch.json()["error"]["details"]["expectedOffset"] == 3
    assert client.get(f"/v1/uploads/{uid}").json()["nextOffset"] == 3
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=3", content=b"de").status_code == 200
    complete = client.post(f"/v1/uploads/{uid}/complete")
    assert complete.status_code == 200
    vid = complete.json()["videoId"]
    assert complete.json()["sha256"] == hashlib.sha256(b"abcde").hexdigest()
    assert client.post(f"/v1/uploads/{uid}/complete").json()["videoId"] == vid
    assert client.get(f"/v1/videos/{vid}").json()["videoId"] == vid
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=5", content=b"x").status_code == 409
    assert client.delete(f"/v1/videos/{vid}").status_code == 204
    assert client.get(f"/v1/videos/{vid}").status_code == 404


def test_finalize_keeps_one_file_and_delete_removes_it(client, storage):
    uid = start_upload(client)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcde")
    vid = client.post(f"/v1/uploads/{uid}/complete").json()["videoId"]
    assert [path.name for path in (storage / "media").iterdir()] == [f"{uid}.bin"]
    assert client.delete(f"/v1/videos/{vid}").status_code == 204
    assert list((storage / "media").iterdir()) == []


def test_upload_overflow(client):
    uid = start_upload(client, size=2)
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abc").status_code == 413
    assert client.get(f"/v1/uploads/{uid}").json()["nextOffset"] == 0


def test_empty_and_too_large_chunk(client):
    uid = start_upload(client, size=CHUNK_SIZE + 1)
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"").status_code == 413
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"z" * (CHUNK_SIZE + 1)).status_code == 413
    assert client.get(f"/v1/uploads/{uid}").json()["nextOffset"] == 0


def test_checksum_mismatch_resets_upload_and_allows_resend(client):
    good = hashlib.sha256(b"abcde").hexdigest()
    uid = start_upload(client, clientSha256=good)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcdX")
    bad = client.post(f"/v1/uploads/{uid}/complete")
    assert bad.status_code == 422
    assert bad.json()["error"]["code"] == "UPLOAD_CHECKSUM_MISMATCH"
    state = client.get(f"/v1/uploads/{uid}").json()
    assert (state["status"], state["nextOffset"], state["videoId"]) == ("UPLOADING", 0, None)
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcde").status_code == 200
    done = client.post(f"/v1/uploads/{uid}/complete")
    assert done.status_code == 200
    assert done.json()["sha256"] == good


def test_bytes_from_an_interrupted_write_do_not_block_the_resume(client, storage):
    # The server died after writing a chunk but before recording it.
    good = hashlib.sha256(b"abcde").hexdigest()
    uid = start_upload(client, clientSha256=good)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abc")
    with (storage / "media" / f"{uid}.bin").open("ab") as part:
        part.write(b"ZZ")
    assert client.get(f"/v1/uploads/{uid}").json()["nextOffset"] == 3
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=3", content=b"de").status_code == 200
    done = client.post(f"/v1/uploads/{uid}/complete")
    assert done.status_code == 200 and done.json()["sha256"] == good


def test_lost_bytes_move_the_offset_back_to_what_is_on_disk(client, storage):
    good = hashlib.sha256(b"abcde").hexdigest()
    uid = start_upload(client, clientSha256=good)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abc")
    with (storage / "media" / f"{uid}.bin").open("r+b") as part:
        part.truncate(1)
    shorter = client.put(f"/v1/uploads/{uid}/chunks?offset=3", content=b"de")
    assert shorter.status_code == 409
    assert shorter.json()["error"]["details"]["expectedOffset"] == 1
    assert client.get(f"/v1/uploads/{uid}").json()["nextOffset"] == 1
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=1", content=b"bcde").status_code == 200
    assert client.post(f"/v1/uploads/{uid}/complete").json()["sha256"] == good


def test_complete_notices_a_missing_file_and_reopens_the_upload(client, storage):
    uid = start_upload(client)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcde")
    (storage / "media" / f"{uid}.bin").unlink()
    gone = client.post(f"/v1/uploads/{uid}/complete")
    assert gone.status_code == 409
    assert gone.json()["error"]["code"] == "UPLOAD_INCOMPLETE"
    assert gone.json()["error"]["details"]["nextOffset"] == 0
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcde").status_code == 200
    assert client.post(f"/v1/uploads/{uid}/complete").status_code == 200


def test_abort_frees_the_bytes_and_closes_the_upload(client, storage):
    uid = start_upload(client)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abc")
    assert client.delete(f"/v1/uploads/{uid}").status_code == 204
    assert list((storage / "media").iterdir()) == []
    assert client.get(f"/v1/uploads/{uid}").json()["status"] == "ABORTED"
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=3", content=b"de").status_code == 409
    assert client.post(f"/v1/uploads/{uid}/complete").status_code == 409
    assert client.delete(f"/v1/uploads/{uid}").status_code == 204
    assert client.delete("/v1/uploads/unknown").status_code == 404


def test_a_finished_upload_can_not_be_aborted(client):
    uid = start_upload(client)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcde")
    vid = client.post(f"/v1/uploads/{uid}/complete").json()["videoId"]
    refused = client.delete(f"/v1/uploads/{uid}")
    assert refused.status_code == 409
    assert refused.json()["error"]["details"]["videoId"] == vid
    assert client.get(f"/v1/videos/{vid}").status_code == 200


def test_running_finalize_blocks_a_second_one_and_a_stale_one_is_taken_over(client, storage):
    uid = start_upload(client)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcde")
    set_upload(storage, uid, status="FINALIZING", finalize_started_at=time.time())
    busy = client.post(f"/v1/uploads/{uid}/complete")
    assert busy.status_code == 409 and busy.json()["error"]["code"] == "UPLOAD_FINALIZING"
    assert client.delete(f"/v1/uploads/{uid}").status_code == 409
    # The process that started it crashed long ago.
    set_upload(storage, uid, finalize_started_at=time.time() - main_module.FINALIZE_STALE_SECONDS - 1)
    done = client.post(f"/v1/uploads/{uid}/complete")
    assert done.status_code == 200
    assert client.get(f"/v1/uploads/{uid}").json()["status"] == "COMPLETED"


def test_restarting_the_app_leaves_a_running_finalize_alone(client, storage):
    uid = start_upload(client)
    client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abcde")
    set_upload(storage, uid, status="FINALIZING", finalize_started_at=time.time())
    main_module.create_app(storage)  # a second process opening the same storage
    assert client.get(f"/v1/uploads/{uid}").json()["status"] == "FINALIZING"
