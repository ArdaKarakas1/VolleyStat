import os
import shutil
import subprocess

import pytest
from fastapi.testclient import TestClient

from app.main import create_app


@pytest.fixture
def client(tmp_path):
    with TestClient(create_app(tmp_path)) as c:
        yield c


@pytest.fixture(scope="session")
def make_clip(tmp_path_factory):
    """Encode a small SYNTHETIC test pattern. No real match footage is used anywhere."""
    if not (shutil.which("ffmpeg") and shutil.which("ffprobe")):
        if os.environ.get("VOLLEYSTAT_REQUIRE_FFMPEG"):
            pytest.fail("ffmpeg/ffprobe are required but not installed")
        pytest.skip("ffmpeg/ffprobe not installed")
    folder = tmp_path_factory.mktemp("clips")
    cache = {}

    def build(name, size="1280x720", rate=30, codec=("-c:v", "libx264", "-pix_fmt", "yuv420p"), seconds=1):
        if name not in cache:
            target = folder / name
            subprocess.run(
                ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", f"testsrc2=size={size}:rate={rate}",
                 "-t", str(seconds), *codec, str(target)],
                check=True,
            )
            cache[name] = target.read_bytes()
        return cache[name]

    return build
