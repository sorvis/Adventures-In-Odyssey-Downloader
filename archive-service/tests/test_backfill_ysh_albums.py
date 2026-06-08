"""Tests for `app.backfill_ysh_albums`.

Stubs the catalog with a hand-built JSON so the test doesn't touch
yourstoryhour.org. Exercises the UPDATE + file-move path end-to-end
against a real /tmp data dir.
"""
from __future__ import annotations
import json
import sqlite3
import sys
from pathlib import Path

import pytest


@pytest.fixture
def isolated_app(tmp_path, monkeypatch):
    """Boot the FastAPI app against a fresh /tmp data dir + a custom
    YSH catalog path, so the backfill module's `from .config import
    YSH_CATALOG_PATH` picks up the test path.
    """
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    catalog_path = tmp_path / "ysh_catalog.json"
    monkeypatch.setenv("ODYSSEY_AUTH_TOKEN", "testtoken")
    monkeypatch.setenv("ODYSSEY_DATA_DIR", str(data_dir))
    monkeypatch.setenv("ODYSSEY_YSH_CATALOG_PATH", str(catalog_path))
    # Drop cached imports so config.py re-evaluates with new env vars.
    for name in [k for k in sys.modules.keys() if k == "app" or k.startswith("app.")]:
        del sys.modules[name]
    from app.main import app
    from fastapi.testclient import TestClient
    with TestClient(app) as c:
        yield {
            "client": c,
            "data_dir": data_dir,
            "catalog_path": catalog_path,
        }


def _write_catalog(path: Path, albums: list[dict]) -> None:
    """Write a YSH catalog JSON that matches the shape build_indexes
    consumes."""
    path.write_text(json.dumps({
        "scrapedAtMs": 0,
        "albumCount": len(albums),
        "albums": albums,
    }))


def _seed_ysh_row(
    data_dir: Path,
    *,
    external_id: str,
    title: str,
    album: str | None = None,
) -> Path:
    """Drop a fake mp3 file + insert a row directly into the DB. We
    bypass the upload endpoint so we can land a row with NULL album
    (which the endpoint now back-fills automatically — defeating the
    point of testing the backfill module)."""
    db_path = data_dir / "episodes.db"
    audio_dir = data_dir / "audio" / "ysh" / "unsorted"
    audio_dir.mkdir(parents=True, exist_ok=True)
    file_path = audio_dir / f"{external_id}.mp3"
    file_path.write_bytes(b"ID3\x04\x00" + b"\x00" * 64)

    c = sqlite3.connect(str(db_path), isolation_level=None)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys=ON")
    c.execute(
        "INSERT INTO episodes "
        "(provider_id, external_id, title, album, description, duration_secs, "
        " file_path, file_size, sha256, source_url) "
        "VALUES ('ysh', ?, ?, ?, NULL, NULL, ?, ?, NULL, NULL)",
        (external_id, title, album, str(file_path), file_path.stat().st_size),
    )
    c.close()
    return file_path


def test_backfill_updates_album_and_moves_file(isolated_app):
    """sku-447 → "Great Stories - Volume 4" — the real case from the
    archive. Verifies both DB and on-disk file land at the new
    canonical path."""
    catalog_path = isolated_app["catalog_path"]
    data_dir = isolated_app["data_dir"]
    _write_catalog(catalog_path, [
        {
            "id": 4,
            "title": "Great Stories - Volume 4",
            "slug": "great-stories-volume-4",
            "code_prefix": "GS-04",
            "lang_code": "en",
            "tracks": [
                {"sku_id": 447, "title": "The Lady of Longpoint",
                 "order_index": 1, "code": "GS-04-02"},
            ],
        },
    ])
    old_path = _seed_ysh_row(
        data_dir,
        external_id="ysh-sku-447",
        title="The Lady of Longpoint",
    )
    assert old_path.exists()

    from app.backfill_ysh_albums import run_backfill
    summary = run_backfill(catalog_path=catalog_path)
    assert summary.rows_scanned == 1
    assert summary.rows_matched == 1
    assert summary.rows_unmatched == 0
    assert summary.files_moved == 1
    assert summary.files_missing == 0

    # File moved to canonical YSH album dir.
    new_path = data_dir / "audio" / "ysh" / "great-stories-volume-4" / "ysh-sku-447-the-lady-of-longpoint.mp3"
    assert new_path.exists()
    assert not old_path.exists()

    # DB row reflects the new album.
    import sqlite3 as sq
    c = sq.connect(str(data_dir / "episodes.db"))
    c.row_factory = sq.Row
    row = c.execute(
        "SELECT album, title, file_path, title_validated_at "
        "FROM episodes WHERE external_id = 'ysh-sku-447'"
    ).fetchone()
    assert row["album"] == "Great Stories - Volume 4"
    assert row["title"] == "The Lady of Longpoint"
    assert row["file_path"] == str(new_path)
    # title_validated_at gets stamped since the catalog confirms the title.
    assert row["title_validated_at"] is not None


def test_backfill_skips_unparseable_external_id(isolated_app):
    catalog_path = isolated_app["catalog_path"]
    data_dir = isolated_app["data_dir"]
    _write_catalog(catalog_path, [])
    _seed_ysh_row(data_dir, external_id="ysh-malformed-id", title="X")

    from app.backfill_ysh_albums import run_backfill
    summary = run_backfill(catalog_path=catalog_path)
    assert summary.rows_unmatched == 1
    assert summary.rows_matched == 0


def test_backfill_skips_sku_not_in_catalog(isolated_app):
    """Catalog may have drifted — out-of-print SKUs disappear from
    the API. Those rows stay flagged but don't error the run."""
    catalog_path = isolated_app["catalog_path"]
    data_dir = isolated_app["data_dir"]
    _write_catalog(catalog_path, [
        {
            "id": 1, "title": "Some Album", "slug": "some-album",
            "code_prefix": None, "lang_code": "en",
            "tracks": [
                {"sku_id": 100, "title": "Foo", "order_index": 0, "code": None},
            ],
        },
    ])
    _seed_ysh_row(data_dir, external_id="ysh-sku-9999", title="Vanished")

    from app.backfill_ysh_albums import run_backfill
    summary = run_backfill(catalog_path=catalog_path)
    assert summary.rows_unmatched == 1
    assert summary.rows_matched == 0


def test_backfill_dry_run_writes_nothing(isolated_app):
    catalog_path = isolated_app["catalog_path"]
    data_dir = isolated_app["data_dir"]
    _write_catalog(catalog_path, [
        {
            "id": 1, "title": "A", "slug": "a", "code_prefix": None,
            "lang_code": "en",
            "tracks": [
                {"sku_id": 1, "title": "T", "order_index": 0, "code": None},
            ],
        },
    ])
    old_path = _seed_ysh_row(data_dir, external_id="ysh-sku-1", title="T")

    from app.backfill_ysh_albums import run_backfill
    summary = run_backfill(catalog_path=catalog_path, dry_run=True)
    assert summary.rows_matched == 1
    assert summary.files_moved == 0  # dry-run skips the file ops
    # Original file untouched.
    assert old_path.exists()
    # DB row still has NULL album.
    import sqlite3 as sq
    c = sq.connect(str(data_dir / "episodes.db"))
    c.row_factory = sq.Row
    row = c.execute(
        "SELECT album FROM episodes WHERE external_id = 'ysh-sku-1'"
    ).fetchone()
    assert row["album"] is None


def test_backfill_idempotent_second_run_finds_zero(isolated_app):
    """A second run after a successful first finds no rows to fix."""
    catalog_path = isolated_app["catalog_path"]
    data_dir = isolated_app["data_dir"]
    _write_catalog(catalog_path, [
        {
            "id": 1, "title": "A", "slug": "a", "code_prefix": None,
            "lang_code": "en",
            "tracks": [
                {"sku_id": 1, "title": "T", "order_index": 0, "code": None},
            ],
        },
    ])
    _seed_ysh_row(data_dir, external_id="ysh-sku-1", title="T")

    from app.backfill_ysh_albums import run_backfill
    run_backfill(catalog_path=catalog_path)
    second = run_backfill(catalog_path=catalog_path)
    assert second.rows_scanned == 0


def test_providers_post_ysh_enriches_album_from_catalog(isolated_app, fake_mp3_bytes):
    """Upstream fix: POST /providers/ysh/episodes with album omitted
    looks up the catalog by sku_id and fills it in. Prevents new YSH
    uploads from landing in Unsorted."""
    import io
    catalog_path = isolated_app["catalog_path"]
    client = isolated_app["client"]
    _write_catalog(catalog_path, [
        {
            "id": 7, "title": "Acts of the Apostles",
            "slug": "acts-of-the-apostles", "code_prefix": None,
            "lang_code": "en",
            "tracks": [
                {"sku_id": 239, "title": "Fire Upon the Earth",
                 "order_index": 0, "code": None},
            ],
        },
    ])
    files = {"audio": ("239.mp3", io.BytesIO(fake_mp3_bytes), "audio/mpeg")}
    # NB: album field intentionally OMITTED — the server must enrich
    # from the YSH catalog by sku_id.
    data = {"external_id": "ysh-sku-239", "title": "Fire Upon the Earth"}
    r = client.post(
        "/providers/ysh/episodes",
        headers={"Authorization": "Bearer testtoken"},
        files=files, data=data,
    )
    assert r.status_code == 201, r.text
    body = r.json()
    assert body["album"] == "Acts of the Apostles"


def test_providers_post_ysh_skips_enrichment_when_album_provided(isolated_app, fake_mp3_bytes):
    """Operator-supplied album wins over catalog lookup — same
    behavior as the AIO branch."""
    import io
    catalog_path = isolated_app["catalog_path"]
    client = isolated_app["client"]
    _write_catalog(catalog_path, [
        {
            "id": 7, "title": "Catalog Album", "slug": "x",
            "code_prefix": None, "lang_code": "en",
            "tracks": [{"sku_id": 1, "title": "T", "order_index": 0, "code": None}],
        },
    ])
    files = {"audio": ("1.mp3", io.BytesIO(fake_mp3_bytes), "audio/mpeg")}
    data = {
        "external_id": "ysh-sku-1",
        "title": "T",
        "album": "Custom Album Override",
    }
    r = client.post(
        "/providers/ysh/episodes",
        headers={"Authorization": "Bearer testtoken"},
        files=files, data=data,
    )
    assert r.status_code == 201
    assert r.json()["album"] == "Custom Album Override"


@pytest.fixture
def fake_mp3_bytes():
    return b"ID3\x04\x00\x00\x00\x00\x00\x00" + b"\xff\xfb\x90\x00" + b"\x00" * 64


def test_backfill_handles_missing_file_gracefully(isolated_app):
    """File deleted out-of-band: DB still updates, files_missing
    counter ticks, run doesn't error."""
    catalog_path = isolated_app["catalog_path"]
    data_dir = isolated_app["data_dir"]
    _write_catalog(catalog_path, [
        {
            "id": 1, "title": "A", "slug": "a", "code_prefix": None,
            "lang_code": "en",
            "tracks": [
                {"sku_id": 1, "title": "T", "order_index": 0, "code": None},
            ],
        },
    ])
    old_path = _seed_ysh_row(data_dir, external_id="ysh-sku-1", title="T")
    old_path.unlink()

    from app.backfill_ysh_albums import run_backfill
    summary = run_backfill(catalog_path=catalog_path)
    assert summary.rows_matched == 1
    assert summary.files_missing == 1
    assert summary.files_moved == 0
