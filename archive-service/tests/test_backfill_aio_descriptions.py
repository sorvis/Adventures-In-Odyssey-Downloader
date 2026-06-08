"""Tests for `app.backfill_aio_descriptions`.

Stubs the oneplace fetcher so the suite never touches the network.
Exercises (a) the happy path (page → PATCH → cursor walks back),
(b) the unreachable bucket (legacy back-catalog rows below
`_MIN_CMS_ID`), and (c) idempotency (a second run with no missing
rows is a no-op).
"""
from __future__ import annotations
import sqlite3
import sys
from pathlib import Path

import pytest


@pytest.fixture
def isolated_app(tmp_path, monkeypatch):
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    monkeypatch.setenv("ODYSSEY_AUTH_TOKEN", "testtoken")
    monkeypatch.setenv("ODYSSEY_DATA_DIR", str(data_dir))
    for name in [k for k in sys.modules.keys() if k == "app" or k.startswith("app.")]:
        del sys.modules[name]
    from app.main import app
    from fastapi.testclient import TestClient
    with TestClient(app) as c:
        yield {"client": c, "data_dir": data_dir}


def _seed_aio_row(
    data_dir: Path,
    *,
    episode_id: int,
    description: str | None = None,
) -> None:
    """Insert an AIO row directly with controlled description state."""
    db_path = data_dir / "episodes.db"
    audio_dir = data_dir / "audio" / "aio" / "unsorted"
    audio_dir.mkdir(parents=True, exist_ok=True)
    file_path = audio_dir / f"{episode_id}.mp3"
    file_path.write_bytes(b"ID3\x04\x00" + b"\x00" * 64)
    c = sqlite3.connect(str(db_path), isolation_level=None)
    c.row_factory = sqlite3.Row
    c.execute(
        "INSERT INTO episodes "
        "(episode_id, provider_id, external_id, title, description, "
        " file_path, file_size, sha256, source_url) "
        "VALUES (?, 'aio', ?, ?, ?, ?, ?, NULL, NULL)",
        (episode_id, str(episode_id), f"Title {episode_id}", description,
         str(file_path), file_path.stat().st_size),
    )
    c.close()


def _fake_fetcher_factory(pages: list[list[dict]]):
    """Returns a fetcher that yields each page in order, then empty.
    `pages[i]` is what gets returned on the i-th call regardless of
    cursor — fine for tests because we control episode_id placement."""
    calls = {"i": 0}

    def fetcher(cursor, page_size):
        i = calls["i"]
        calls["i"] += 1
        if i < len(pages):
            return pages[i]
        return []

    return fetcher, calls


def test_backfill_patches_descriptions_from_oneplace(isolated_app):
    data_dir = isolated_app["data_dir"]
    _seed_aio_row(data_dir, episode_id=1278380)
    _seed_aio_row(data_dir, episode_id=1278381)
    fetcher, calls = _fake_fetcher_factory([
        [
            {"episodeId": 1278381, "description": "Synopsis for 381."},
            {"episodeId": 1278380, "description": "Synopsis for 380."},
        ],
        # Second call returns empty → terminate. (Cursor walking is
        # also tested below — this page covers both eids in one shot.)
    ])
    from app.backfill_aio_descriptions import run_backfill
    result = run_backfill(fetcher=fetcher, sleep_secs=0)
    assert result.rows_patched == 2
    assert result.rows_unreachable == 0
    # DB rows now have descriptions.
    c = sqlite3.connect(str(data_dir / "episodes.db"))
    c.row_factory = sqlite3.Row
    for eid, expected in [(1278380, "Synopsis for 380."),
                          (1278381, "Synopsis for 381.")]:
        row = c.execute(
            "SELECT description FROM episodes WHERE episode_id = ?", (eid,)
        ).fetchone()
        assert row["description"] == expected
    assert calls["i"] >= 1


def test_backfill_marks_legacy_broadcast_ids_as_unreachable(isolated_app):
    """Episodes like 82 'Heatwave' came from pre-oneplace imports —
    they're below _MIN_CMS_ID and the API can't return them, so they
    sit in the unreachable bucket without burning fetches."""
    data_dir = isolated_app["data_dir"]
    _seed_aio_row(data_dir, episode_id=82)
    _seed_aio_row(data_dir, episode_id=200)
    fetcher, calls = _fake_fetcher_factory([])  # never invoked
    from app.backfill_aio_descriptions import run_backfill
    result = run_backfill(fetcher=fetcher, sleep_secs=0)
    assert result.rows_unreachable == 2
    assert result.rows_patched == 0
    assert calls["i"] == 0  # didn't even hit oneplace — no reachable eids


def test_backfill_walks_cursor_backward_across_pages(isolated_app):
    """Multi-page walk: page 1 covers eid 1278381, page 2 covers
    1278360 — cursor should drop from default anchor to page-1's
    minimum, then page-2's minimum."""
    data_dir = isolated_app["data_dir"]
    _seed_aio_row(data_dir, episode_id=1278381)
    _seed_aio_row(data_dir, episode_id=1278360)
    captured = {"cursors": []}

    def fetcher(cursor, page_size):
        captured["cursors"].append(cursor)
        if len(captured["cursors"]) == 1:
            return [{"episodeId": 1278381, "description": "page1"}]
        if len(captured["cursors"]) == 2:
            return [{"episodeId": 1278360, "description": "page2"}]
        return []

    from app.backfill_aio_descriptions import run_backfill
    result = run_backfill(fetcher=fetcher, sleep_secs=0)
    assert result.rows_patched == 2
    # First cursor = max+1 = 1278382; second cursor = page1 oldest = 1278381.
    assert captured["cursors"][0] == 1278382
    assert captured["cursors"][1] == 1278381


def test_backfill_skips_when_description_empty_in_api(isolated_app):
    """oneplace sometimes returns an episode with description=''
    or null — don't blow up, just skip."""
    data_dir = isolated_app["data_dir"]
    _seed_aio_row(data_dir, episode_id=1278381)
    fetcher, _ = _fake_fetcher_factory([
        [{"episodeId": 1278381, "description": ""}],
    ])
    from app.backfill_aio_descriptions import run_backfill
    result = run_backfill(fetcher=fetcher, sleep_secs=0)
    assert result.rows_patched == 0


def test_backfill_idempotent_second_run_finds_zero(isolated_app):
    """Once a row has a description, the next run shouldn't touch it."""
    data_dir = isolated_app["data_dir"]
    _seed_aio_row(data_dir, episode_id=1278381, description="already there")
    fetcher, calls = _fake_fetcher_factory([])
    from app.backfill_aio_descriptions import run_backfill
    result = run_backfill(fetcher=fetcher, sleep_secs=0)
    assert result.rows_scanned == 0
    assert calls["i"] == 0


def test_backfill_dry_run_does_not_write_db(isolated_app):
    data_dir = isolated_app["data_dir"]
    _seed_aio_row(data_dir, episode_id=1278381)
    fetcher, _ = _fake_fetcher_factory([
        [{"episodeId": 1278381, "description": "Would write this."}],
    ])
    from app.backfill_aio_descriptions import run_backfill
    result = run_backfill(fetcher=fetcher, sleep_secs=0, dry_run=True)
    assert result.rows_patched == 1
    # DB row still NULL.
    c = sqlite3.connect(str(data_dir / "episodes.db"))
    c.row_factory = sqlite3.Row
    row = c.execute(
        "SELECT description FROM episodes WHERE episode_id = 1278381"
    ).fetchone()
    assert row["description"] is None


def test_backfill_stops_on_fetcher_exception(isolated_app):
    """A network blip mid-walk shouldn't crash the run — log + bail
    with whatever was patched so far."""
    data_dir = isolated_app["data_dir"]
    _seed_aio_row(data_dir, episode_id=1278381)
    _seed_aio_row(data_dir, episode_id=1278360)

    def fetcher(cursor, page_size):
        if cursor == 1278382:
            return [{"episodeId": 1278381, "description": "got it"}]
        raise RuntimeError("simulated network blip")

    from app.backfill_aio_descriptions import run_backfill
    result = run_backfill(fetcher=fetcher, sleep_secs=0)
    # The first page landed before the blip; only 1278381 patched.
    assert result.rows_patched == 1
    assert result.pages_fetched == 1
