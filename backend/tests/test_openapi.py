"""The committed OpenAPI snapshot (docs/openapi-local-dev.json) must describe the running app."""
import json
from pathlib import Path

from app.main import create_app

SNAPSHOT = Path(__file__).resolve().parents[2] / "docs" / "openapi-local-dev.json"
REGENERATE = (
    "docs/openapi-local-dev.json is stale. Regenerate it from the backend folder with:\n"
    "python -c \"import json, pathlib, tempfile; from app.main import create_app; "
    "pathlib.Path('../docs/openapi-local-dev.json').write_text(json.dumps(create_app(tempfile.mkdtemp(), analyzer='none')"
    ".openapi(), indent=2, ensure_ascii=False) + '\\n', encoding='utf-8', newline='\\n')\""
)
# (path, method) of every route of docs/API_V1.md. A regenerated snapshot can not silently lose one.
CONTRACT_ROUTES = [
    ("/v1/health", "get"),
    ("/v1/matches", "post"),
    ("/v1/matches/{match_id}", "get"),
    ("/v1/matches/{match_id}/rules", "put"),
    ("/v1/videos/{video_id}/analyses", "post"),
    ("/v1/analyses/{job_id}", "get"),
    ("/v1/videos/{video_id}/analyses/latest", "get"),
    ("/v1/analyses/{job_id}/cancel", "post"),
    ("/v1/analyses/{job_id}/rallies", "get"),
    ("/v1/analyses/{job_id}/review-cards", "get"),
    ("/v1/review-cards/{card_id}/decisions", "post"),
    ("/v1/review-cards/{card_id}/reopen", "post"),
    ("/v1/videos/{video_id}/content", "get"),
    ("/v1/matches/{match_id}/report", "get"),
    ("/v1/analyses/{job_id}/rallies", "post"),
    ("/v1/rallies/{rally_id}/move", "post"),
    ("/v1/rallies/{rally_id}/split", "post"),
    ("/v1/rallies/{rally_id}/merge-previous", "post"),
    ("/v1/analyses/{job_id}/rally-list/confirm", "post"),
    ("/v1/auth/register", "post"),
    ("/v1/auth/login", "post"),
    ("/v1/auth/logout", "post"),
    ("/v1/me", "get"),
    ("/v1/me/display-name", "put"),
    ("/v1/me/password", "put"),
    ("/v1/me/delete", "post"),
]


def test_the_snapshot_is_the_openapi_document_of_the_app(storage):
    spec = create_app(storage, analyzer="none").openapi()
    assert SNAPSHOT.read_text(encoding="utf-8") == json.dumps(spec, indent=2, ensure_ascii=False) + "\n", REGENERATE


def test_the_openapi_document_does_not_depend_on_the_analyzer(storage):
    assert create_app(storage, analyzer="demo").openapi() == create_app(storage, analyzer="none").openapi()


def test_the_app_is_version_0_8_0_and_serves_every_route_of_the_contract(storage):
    spec = create_app(storage, analyzer="none").openapi()
    assert spec["info"]["version"] == "0.8.0"
    missing = [(path, method) for path, method in CONTRACT_ROUTES if method not in spec["paths"].get(path, {})]
    assert missing == []


def test_the_contract_lists_the_rule_values_the_server_accepts(storage):
    schema = create_app(storage, analyzer="none").openapi()["components"]["schemas"]["MatchRulesInput"]
    assert {name: field["enum"] for name, field in schema["properties"].items()} == {
        "setsToWin": [2, 3], "setTarget": [25, 21, 15], "decidingSetTarget": [25, 21, 15]}
    assert schema["additionalProperties"] is False


def test_a_rule_change_names_the_rule_its_user_saw(storage):
    schemas = create_app(storage, analyzer="none").openapi()["components"]["schemas"]
    change = schemas["MatchRulesChange"]
    assert set(change["required"]) == {"setsToWin", "setTarget", "decidingSetTarget", "expectedRules"}
    assert change["properties"]["expectedRules"] == {"$ref": "#/components/schemas/MatchRulesInput"}
    assert change["additionalProperties"] is False


def test_the_routes_that_need_a_session_say_so_and_the_others_do_not(storage):
    spec = create_app(storage, analyzer="none").openapi()
    session = {("/v1/auth/logout", "post"), ("/v1/me", "get"), ("/v1/me/display-name", "put"),
               ("/v1/me/password", "put"), ("/v1/me/delete", "post")}
    marked = {(path, method) for path, operations in spec["paths"].items() for method, operation in operations.items()
              if operation.get("security")}
    assert marked == session
    assert spec["components"]["securitySchemes"]["HTTPBearer"]["scheme"] == "bearer"
