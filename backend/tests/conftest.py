import os
import shutil
import subprocess

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from tests.helpers import NO_SESSION, STRANGER, As, as_account, sign_in


@pytest.fixture(autouse=True)
def no_analyzer_from_the_shell(monkeypatch):
    # A developer shell may export VOLLEYSTAT_ANALYZER. Each test names its analyzer itself.
    monkeypatch.delenv("VOLLEYSTAT_ANALYZER", raising=False)


@pytest.fixture(autouse=True)
def no_disk_floor(monkeypatch):
    # The server keeps 20 GiB of the disk free by default, and a test runner may have less. The
    # tests of the floor set their own floor and patch the free space (tests/test_quotas.py).
    monkeypatch.setenv("VOLLEYSTAT_DISK_FLOOR_GIB", "0")


@pytest.fixture
def storage(tmp_path):
    return tmp_path


@pytest.fixture
def client(storage):
    """A client signed in as the owner account of this storage folder (tests.helpers.OWNER)."""
    with TestClient(create_app(storage)) as c:
        yield sign_in(c, storage)


@pytest.fixture
def anonymous(client):
    """The same app, every request without a session. Not a second TestClient: its lifespan exit
    would close the app's shared hash pool."""
    return As(client, NO_SESSION)


@pytest.fixture
def stranger(client, storage):
    """Another signed-in account on the same storage."""
    return as_account(client, storage, STRANGER)


@pytest.fixture
def link_account(client, storage):
    """Make an account that opened through a link (INVITE by default), seeded straight into SQLite."""
    def make(username="test.davetli", signup_via="INVITE"):
        return as_account(client, storage, username, signup_via)
    return make


@pytest.fixture(scope="session")
def make_clip(tmp_path_factory):
    """Encode a small SYNTHETIC test pattern. No real match footage is used anywhere."""
    if not (shutil.which("ffmpeg") and shutil.which("ffprobe")):
        if os.environ.get("VOLLEYSTAT_REQUIRE_FFMPEG"):
            pytest.fail("ffmpeg/ffprobe are required but not installed")
        pytest.skip("ffmpeg/ffprobe not installed")
    folder = tmp_path_factory.mktemp("clips")
    cache = {}

    def build(name, size="1280x720", rate="30", codec=("-c:v", "libx264", "-pix_fmt", "yuv420p"),
              seconds=1, audio_seconds=None, output=()):
        if name not in cache:
            target = folder / name
            command = ["ffmpeg", "-v", "error", "-f", "lavfi", "-t", str(seconds), "-i", f"testsrc2=size={size}:rate={rate}"]
            if audio_seconds:
                command += ["-f", "lavfi", "-t", str(audio_seconds), "-i", "sine=frequency=440", "-c:a", "aac"]
            subprocess.run([*command, *codec, *output, str(target)], check=True)
            cache[name] = target.read_bytes()
        return cache[name]

    return build
