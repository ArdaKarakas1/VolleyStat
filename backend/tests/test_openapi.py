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
]


def test_the_snapshot_is_the_openapi_document_of_the_app(storage):
    spec = create_app(storage, analyzer="none").openapi()
    assert SNAPSHOT.read_text(encoding="utf-8") == json.dumps(spec, indent=2, ensure_ascii=False) + "\n", REGENERATE


def test_the_openapi_document_does_not_depend_on_the_analyzer(storage):
    assert create_app(storage, analyzer="demo").openapi() == create_app(storage, analyzer="none").openapi()


def test_the_app_is_version_0_4_0_and_serves_every_route_of_the_contract(storage):
    spec = create_app(storage, analyzer="none").openapi()
    assert spec["info"]["version"] == "0.4.1"
    missing = [(path, method) for path, method in CONTRACT_ROUTES if method not in spec["paths"].get(path, {})]
    assert missing == []
