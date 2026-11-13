"""Match metadata (docs/API_V1.md, section 1)."""
import sqlite3

import pytest

from app.main import create_app
from fastapi.testclient import TestClient

TEAMS = {"homeTeamName": "A Takımı", "awayTeamName": "B Takımı"}


def test_metadata_is_optional_and_kind_defaults_to_training(client):
    created = client.post("/v1/matches", json=TEAMS)
    assert created.status_code == 201
    body = created.json()
    assert (body["name"], body["playedOn"], body["venue"], body["kind"]) == (None, None, None, "TRAINING")
    assert client.get(f"/v1/matches/{body['id']}").json() == body


def test_metadata_is_stored_trimmed_and_read_back(client):
    created = client.post("/v1/matches", json={
        **TEAMS, "name": "  Salı antrenman maçı ", "playedOn": "2026-11-12", "venue": "Okul salonu", "kind": "OFFICIAL"})
    assert created.status_code == 201, created.text
    body = created.json()
    assert body == {
        "id": body["id"], "homeTeamName": "A Takımı", "awayTeamName": "B Takımı",
        "name": "Salı antrenman maçı", "playedOn": "2026-11-12", "venue": "Okul salonu", "kind": "OFFICIAL",
        "status": "DRAFT",
    }
    assert client.get(f"/v1/matches/{body['id']}").json() == body


def test_explicit_nulls_and_blank_strings_mean_not_given(client):
    body = client.post("/v1/matches", json={**TEAMS, "name": None, "playedOn": None, "venue": "   ", "kind": None}).json()
    assert (body["name"], body["playedOn"], body["venue"], body["kind"]) == (None, None, None, "TRAINING")
    assert client.post("/v1/matches", json={**TEAMS, "name": "", "playedOn": ""}).json()["playedOn"] is None


@pytest.mark.parametrize("field, value", [
    ("name", "x" * 81),
    ("venue", "x" * 81),
    ("playedOn", "12.11.2026"),
    ("playedOn", "2026-11-12T10:00:00"),
    ("playedOn", "2026-13-01"),
    ("playedOn", "2026-02-30"),
    ("playedOn", "0000-01-01"),
    ("playedOn", 20261112),
    ("kind", "FRIENDLY"),
    ("kind", "training"),
])
def test_invalid_metadata_is_rejected(client, field, value):
    rejected = client.post("/v1/matches", json={**TEAMS, field: value})
    assert rejected.status_code == 422
    assert rejected.json()["error"]["code"] == "VALIDATION_ERROR"
    assert any(field in entry["location"] for entry in rejected.json()["error"]["details"]["fields"])


def test_the_longest_allowed_name_and_venue_are_accepted(client):
    body = client.post("/v1/matches", json={**TEAMS, "name": "n" * 80, "venue": "v" * 80}).json()
    assert (len(body["name"]), len(body["venue"])) == (80, 80)


def test_a_match_stored_by_version_0_3_gets_the_new_fields(storage):
    with sqlite3.connect(storage / "volleystat.sqlite3") as old:
        old.execute("CREATE TABLE matches (id TEXT PRIMARY KEY, home_team TEXT NOT NULL, away_team TEXT NOT NULL,"
                    " created_at TEXT NOT NULL DEFAULT (datetime('now')))")
        old.execute("INSERT INTO matches(id, home_team, away_team) VALUES('m1', 'A Takımı', 'B Takımı')")
    with TestClient(create_app(storage)) as client:
        body = client.get("/v1/matches/m1").json()
        assert (body["name"], body["playedOn"], body["venue"], body["kind"]) == (None, None, None, "TRAINING")
        assert client.post("/v1/matches", json={**TEAMS, "kind": "OFFICIAL"}).json()["kind"] == "OFFICIAL"
