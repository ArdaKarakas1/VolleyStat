"""Match metadata (docs/API_V1.md, section 1)."""
import itertools
import sqlite3
import uuid

import pytest

from app.main import create_app
from fastapi.testclient import TestClient

TEAMS = {"homeTeamName": "A Takımı", "awayTeamName": "B Takımı"}
FIVB = {"setsToWin": 3, "setTarget": 25, "decidingSetTarget": 15, "winBy": 2, "decidingSetNumber": 5}
BEST_OF_THREE_TO_21 = {"setsToWin": 2, "setTarget": 21, "decidingSetTarget": 15}


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
        "rules": FIVB, "status": "DRAFT",
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
        assert body["rules"] == FIVB
        assert client.post("/v1/matches", json={**TEAMS, "kind": "OFFICIAL"}).json()["kind"] == "OFFICIAL"


# --- the match rule --------------------------------------------------------------------------

def test_a_match_without_a_rule_counts_with_the_fivb_rule(client):
    for body in (TEAMS, {**TEAMS, "rules": None}):
        created = client.post("/v1/matches", json=body)
        assert created.status_code == 201 and created.json()["rules"] == FIVB
        put = client.put(f"/v1/matches/{uuid.uuid4()}", json=body)
        assert put.status_code == 201 and put.json()["rules"] == FIVB


def test_a_chosen_rule_is_stored_and_read_back(client):
    expected = {**BEST_OF_THREE_TO_21, "winBy": 2, "decidingSetNumber": 3}
    created = client.post("/v1/matches", json={**TEAMS, "rules": BEST_OF_THREE_TO_21})
    assert created.status_code == 201 and created.json()["rules"] == expected
    assert client.get(f"/v1/matches/{created.json()['id']}").json() == created.json()
    match_id = str(uuid.uuid4())
    put = client.put(f"/v1/matches/{match_id}", json={**TEAMS, "rules": BEST_OF_THREE_TO_21})
    assert put.status_code == 201 and put.json()["rules"] == expected
    assert client.get(f"/v1/matches/{match_id}").json() == put.json()


@pytest.mark.parametrize("sets, target, deciding", list(itertools.product((2, 3), (25, 21, 15), (25, 21, 15))))
def test_every_offered_rule_is_accepted(client, sets, target, deciding):
    rules = {"setsToWin": sets, "setTarget": target, "decidingSetTarget": deciding}
    created = client.post("/v1/matches", json={**TEAMS, "rules": rules})
    assert created.status_code == 201, created.text
    assert created.json()["rules"] == {**rules, "winBy": 2, "decidingSetNumber": 2 * sets - 1}


@pytest.mark.parametrize("rules", [
    {**BEST_OF_THREE_TO_21, "setsToWin": 1},
    {**BEST_OF_THREE_TO_21, "setsToWin": 4},
    {**BEST_OF_THREE_TO_21, "setsToWin": "3"},
    {**BEST_OF_THREE_TO_21, "setsToWin": 3.0},
    {**BEST_OF_THREE_TO_21, "setsToWin": True},
    {**BEST_OF_THREE_TO_21, "setTarget": 20},
    {**BEST_OF_THREE_TO_21, "setTarget": "25"},
    {**BEST_OF_THREE_TO_21, "setTarget": None},
    {**BEST_OF_THREE_TO_21, "decidingSetTarget": 14},
    {"setsToWin": 2, "setTarget": 21},
    {**BEST_OF_THREE_TO_21, "winBy": 2},
    "FIVB",
    [],
], ids=["sets-1", "sets-4", "sets-text", "sets-float", "sets-bool", "target-20", "target-text", "target-null",
        "deciding-14", "missing-key", "extra-key", "a-word", "a-list"])
def test_an_invalid_rule_is_refused_and_names_its_field(client, storage, rules):
    for refused in (client.post("/v1/matches", json={**TEAMS, "rules": rules}),
                    client.put(f"/v1/matches/{uuid.uuid4()}", json={**TEAMS, "rules": rules})):
        assert refused.status_code == 422, refused.text
        assert refused.json()["error"]["code"] == "VALIDATION_ERROR"
        assert any("rules" in entry["location"] for entry in refused.json()["error"]["details"]["fields"])
    with sqlite3.connect(storage / "volleystat.sqlite3") as db:
        assert db.execute("SELECT COUNT(*) FROM matches").fetchone()[0] == 0
