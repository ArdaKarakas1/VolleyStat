"""PUT /v1/matches/{id}: a client that picks the id can always address the match afterwards."""
import uuid

from tests.helpers import rows
from tests.test_api import create_upload

BODY = {"homeTeamName": "Takım A", "awayTeamName": "Takım B", "name": "Hazırlık", "playedOn": "2026-11-13", "venue": "Salon", "kind": "TRAINING"}


def test_a_match_is_created_under_the_id_the_client_chose(client, storage):
    match_id = str(uuid.uuid4())
    created = client.put(f"/v1/matches/{match_id}", json=BODY)
    assert created.status_code == 201 and created.json()["id"] == match_id
    assert client.get(f"/v1/matches/{match_id}").json() == created.json()
    # The id works like any other: an upload opens under it.
    assert create_upload(client, match_id).status_code == 201


def test_repeating_the_request_returns_the_same_match_and_creates_no_second_one(client, storage):
    match_id = str(uuid.uuid4())
    first = client.put(f"/v1/matches/{match_id}", json=BODY)
    again = client.put(f"/v1/matches/{match_id}", json=BODY)
    assert (first.status_code, again.status_code) == (201, 200)
    assert again.json() == first.json()
    assert len(rows(storage, "SELECT 1 FROM matches")) == 1


def test_a_repeat_with_other_content_does_not_change_the_stored_match(client):
    match_id = str(uuid.uuid4())
    first = client.put(f"/v1/matches/{match_id}", json=BODY).json()
    again = client.put(f"/v1/matches/{match_id}", json={**BODY, "homeTeamName": "Başka Takım", "venue": "Başka Salon"})
    assert again.status_code == 200 and again.json() == first


def test_an_id_that_is_not_a_canonical_uuid_is_refused(client, storage):
    for bad in ("no-such-match", "1234", str(uuid.uuid4()).upper(), uuid.uuid4().hex, f"{{{uuid.uuid4()}}}"):
        refused = client.put(f"/v1/matches/{bad}", json=BODY)
        assert refused.status_code == 422 and refused.json()["error"]["code"] == "MATCH_ID_INVALID", bad
    assert rows(storage, "SELECT 1 FROM matches") == []


def test_the_body_is_checked_like_the_body_of_a_post(client, storage):
    refused = client.put(f"/v1/matches/{uuid.uuid4()}", json={**BODY, "awayTeamName": BODY["homeTeamName"]})
    assert refused.status_code == 422
    assert rows(storage, "SELECT 1 FROM matches") == []


def test_a_deleted_match_is_gone_and_its_id_can_be_created_again(client):
    match_id = str(uuid.uuid4())
    assert client.put(f"/v1/matches/{match_id}", json=BODY).status_code == 201
    assert client.delete(f"/v1/matches/{match_id}").status_code == 204
    assert client.get(f"/v1/matches/{match_id}").status_code == 404
    assert client.put(f"/v1/matches/{match_id}", json=BODY).status_code == 201


def test_deleting_an_id_the_server_never_saw_answers_match_not_found(client):
    # What a phone does when the answer to its first request was lost and the request never arrived.
    missing = client.delete(f"/v1/matches/{uuid.uuid4()}")
    assert missing.status_code == 404 and missing.json()["error"]["code"] == "MATCH_NOT_FOUND"
