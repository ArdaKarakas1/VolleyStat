import hashlib

from app.main import CHUNK_SIZE


def create_match(c):
    resp = c.post("/v1/matches", json={"homeTeamName": "Takım A", "awayTeamName": "Takım B"})
    assert resp.status_code == 201
    return resp.json()["id"]


def create_upload(c, match_id, size=5, **overrides):
    body = {"fileName": "match.mp4", "sizeBytes": size, "mimeType": "video/mp4", "rightsAttested": True, "allowTraining": False}
    body.update(overrides)
    return c.post(f"/v1/matches/{match_id}/uploads", json=body)


def test_health(client):
    body = client.get("/v1/health").json()
    assert body["ai"] == "not-configured"
    assert body["mediaProbe"] in ("available", "unavailable")


def test_match_validation(client):
    assert client.post("/v1/matches", json={"homeTeamName": "A", "awayTeamName": "B"}).status_code == 422
    assert client.post("/v1/matches", json={"homeTeamName": "A Team", "awayTeamName": "a team"}).status_code == 422


def test_upload_rights_training_and_path(client):
    m = create_match(client)
    assert create_upload(client, m, rightsAttested=False).status_code == 422
    assert create_upload(client, m, allowTraining=True).status_code == 422
    assert create_upload(client, m, fileName="../secret.mp4").status_code == 422
    assert create_upload(client, m, mimeType="image/png").status_code == 422
    assert create_upload(client, m, clientSha256="not-a-hash").status_code == 422
    assert create_upload(client, "not-a-match").status_code == 404


def test_upload_chunk_end_to_end_and_replay(client):
    m = create_match(client)
    upload = create_upload(client, m)
    assert upload.status_code == 201
    uid = upload.json()["uploadId"]
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
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=5", content=b"x").status_code == 409
    assert client.delete(f"/v1/videos/{vid}").status_code == 204
    assert client.get(f"/v1/videos/{vid}").status_code == 404


def test_upload_overflow(client):
    m = create_match(client)
    uid = create_upload(client, m, size=2).json()["uploadId"]
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"abc").status_code == 413
    assert client.get(f"/v1/uploads/{uid}").json()["nextOffset"] == 0


def test_empty_and_too_large_chunk(client):
    m = create_match(client)
    uid = create_upload(client, m, size=CHUNK_SIZE + 1).json()["uploadId"]
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"").status_code == 413
    assert client.put(f"/v1/uploads/{uid}/chunks?offset=0", content=b"z" * (CHUNK_SIZE + 1)).status_code == 413
    assert client.get(f"/v1/uploads/{uid}").json()["nextOffset"] == 0


def test_checksum_mismatch_resets_upload_and_allows_resend(client):
    m = create_match(client)
    good = hashlib.sha256(b"abcde").hexdigest()
    uid = create_upload(client, m, clientSha256=good).json()["uploadId"]
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
