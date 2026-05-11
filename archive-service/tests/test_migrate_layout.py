"""
NAS layout migration — audio/<slug>/... → audio/aio/<slug>/...

Verifies the one-shot move that pre-multi-show installs need to walk
through on first start of the new server. Idempotent: marker-gated so
subsequent starts skip the scan; fresh installs drop the marker
without creating audio/aio/ until a real episode lands.
"""
from __future__ import annotations

from pathlib import Path

import pytest


def _seed_db_row(data_dir: Path, episode_id: int, file_path: Path) -> None:
    """Insert a legacy AIO row pointing at `file_path`. Goes through the
    raw sqlite3 driver so the test doesn't depend on the HTTP route
    shape — those tests live alongside the API ones."""
    import sqlite3
    db_path = data_dir / "episodes.db"
    conn = sqlite3.connect(db_path, isolation_level=None)
    try:
        # Pre-migration table shape (matches v1 before provider_id /
        # external_id columns landed). The new migrator copes either way.
        conn.execute("""
            CREATE TABLE IF NOT EXISTS episodes (
                episode_id     INTEGER PRIMARY KEY,
                title          TEXT NOT NULL,
                air_date       TEXT,
                album          TEXT,
                description    TEXT,
                duration_secs  INTEGER,
                file_path      TEXT NOT NULL,
                file_size      INTEGER NOT NULL,
                sha256         TEXT,
                source_url     TEXT,
                archived_at    TEXT NOT NULL DEFAULT (datetime('now'))
            )
        """)
        conn.execute(
            "INSERT OR REPLACE INTO episodes "
            "(episode_id, title, album, file_path, file_size) "
            "VALUES (?, ?, ?, ?, ?)",
            (episode_id, f"Ep {episode_id}", "the-adventure-begins", str(file_path), 100),
        )
    finally:
        conn.close()


@pytest.fixture
def env(tmp_path, monkeypatch):
    data = tmp_path / "data"
    (data / "audio").mkdir(parents=True)
    monkeypatch.setenv("ODYSSEY_AUTH_TOKEN", "x")
    monkeypatch.setenv("ODYSSEY_DATA_DIR", str(data))
    # Drop cached imports so config.py re-reads the env vars.
    import sys
    for name in [k for k in list(sys.modules) if k == "app" or k.startswith("app.")]:
        del sys.modules[name]
    return data


def test_legacy_dirs_move_under_aio_and_db_paths_rewrite(env):
    audio = env / "audio"
    # Pre-migration layout: top-level slug dir with one file.
    legacy_dir = audio / "the-adventure-begins"
    legacy_dir.mkdir()
    legacy_file = legacy_dir / "1278294-clutter.mp3"
    legacy_file.write_bytes(b"fake-mp3")
    # Pre-migration DB row pointing at the legacy path.
    _seed_db_row(env, 1278294, legacy_file)

    from app import db
    from app.migrate_layout import migrate_layout
    # Run schema migration first so the file_path REPLACE has the new
    # columns available; the layout migrator doesn't need them but main
    # always runs init() before migrate_layout().
    db.init()
    migrate_layout()

    # File moved.
    new_path = audio / "aio" / "the-adventure-begins" / "1278294-clutter.mp3"
    assert new_path.exists()
    assert not legacy_file.exists()
    # Marker dropped.
    assert (audio / ".aio-layout-v1").exists()
    # DB row rewritten.
    import sqlite3
    conn = sqlite3.connect(env / "episodes.db")
    try:
        row = conn.execute(
            "SELECT file_path FROM episodes WHERE episode_id = ?", (1278294,)
        ).fetchone()
        assert row[0] == str(new_path)
    finally:
        conn.close()


def test_second_run_is_a_no_op_even_when_legacy_dirs_reappear(env):
    audio = env / "audio"
    # First run on an empty audio dir just drops the marker.
    from app import db
    from app.migrate_layout import migrate_layout
    db.init()
    migrate_layout()
    assert (audio / ".aio-layout-v1").exists()
    assert not (audio / "aio").exists()  # nothing to migrate

    # Drop a legacy-shaped dir POST-marker. The migrator must NOT touch
    # it — the marker is the gate.
    (audio / "stray-album").mkdir()
    (audio / "stray-album" / "9.mp3").write_bytes(b"x")
    migrate_layout()
    assert (audio / "stray-album").exists()
    assert (audio / "stray-album" / "9.mp3").exists()
    assert not (audio / "aio").exists()


def test_fresh_install_drops_marker_without_creating_aio(env):
    audio = env / "audio"
    from app import db
    from app.migrate_layout import migrate_layout
    db.init()
    migrate_layout()
    assert (audio / ".aio-layout-v1").exists()
    assert not (audio / "aio").exists()


def test_reserved_top_level_names_are_skipped(env):
    audio = env / "audio"
    # Pre-create the post-migration layout AND a hidden _unmatched dir.
    (audio / "aio").mkdir()
    (audio / "_unmatched").mkdir()
    (audio / "_unmatched" / "weird.mp3").write_bytes(b"x")
    # And a legacy slug dir alongside.
    legacy_dir = audio / "legit-album"
    legacy_dir.mkdir()
    (legacy_dir / "1.mp3").write_bytes(b"y")

    from app import db
    from app.migrate_layout import migrate_layout
    db.init()
    migrate_layout()

    # _unmatched is untouched.
    assert (audio / "_unmatched" / "weird.mp3").exists()
    # legit-album moved.
    assert (audio / "aio" / "legit-album" / "1.mp3").exists()
    assert not legacy_dir.exists()


def test_same_name_dst_merges_files_without_clobbering(env):
    audio = env / "audio"
    aio_dir = audio / "aio" / "the-adventure-begins"
    aio_dir.mkdir(parents=True)
    (aio_dir / "1-existing.mp3").write_bytes(b"old")

    legacy_dir = audio / "the-adventure-begins"
    legacy_dir.mkdir()
    (legacy_dir / "2-new.mp3").write_bytes(b"new")
    # File with the same name as an existing aio/ entry — the existing
    # file under aio/ stays; the legacy duplicate is left behind for
    # the operator to clean up manually.
    (legacy_dir / "1-existing.mp3").write_bytes(b"legacy-duplicate")

    from app import db
    from app.migrate_layout import migrate_layout
    db.init()
    migrate_layout()

    # New file moved into aio/.
    assert (aio_dir / "2-new.mp3").exists()
    # Existing aio/ entry preserved (not overwritten).
    assert (aio_dir / "1-existing.mp3").read_bytes() == b"old"
