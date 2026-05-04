"""
Common pytest fixtures for the archive-service test suite.

Each test gets a fresh isolated /tmp dir and a fresh in-process FastAPI
app — no Docker, no real /data, no network. The auth token is fixed at
"testtoken" so tests can assert exact request shapes.
"""
from __future__ import annotations
import os
import shutil
import sys
import tempfile
import uuid
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

# Make sure the test process can import `app` — the project root is the
# parent of `tests/`. (We don't ship a setup.py / pyproject.toml so we
# rely on path tweaks rather than `pip install -e .`.)
ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

TOKEN = "testtoken"


@pytest.fixture
def data_dir(tmp_path: Path):
    """A clean per-test data dir so tests don't share SQLite state."""
    d = tmp_path / "data"
    d.mkdir()
    yield d
    # tmp_path is auto-cleaned by pytest; nothing else to do.


@pytest.fixture
def client(data_dir, monkeypatch):
    """A FastAPI TestClient pointed at a fresh data dir.

    Env vars must be set BEFORE importing `app` because config.py reads
    them at module-load time. We re-import inside the fixture so each
    test starts from a clean slate.

    Yields the client from a `with` block so the FastAPI lifespan
    handler fires — the lifespan is what creates the SQLite schema.
    """
    monkeypatch.setenv("ODYSSEY_AUTH_TOKEN", TOKEN)
    monkeypatch.setenv("ODYSSEY_DATA_DIR", str(data_dir))
    # Drop any cached imports of `app` so config.py is re-evaluated.
    for name in [k for k in sys.modules.keys() if k == "app" or k.startswith("app.")]:
        del sys.modules[name]
    from app.main import app
    with TestClient(app) as c:
        yield c


@pytest.fixture
def auth_headers():
    return {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture
def fake_mp3_bytes() -> bytes:
    """Minimal valid-ish MP3 bytes — an ID3v2 header + a sync frame.

    Doesn't need to be playable; the service only stores bytes and SHAs them.
    """
    return b"ID3\x04\x00\x00\x00\x00\x00\x00" + b"\xff\xfb\x90\x00" + b"\x00" * 64
