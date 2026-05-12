"""Tests for the YSH drop-folder importer (step 13).

Drives the full flow against a real on-disk drop folder + a real
SQLite DB, just like the AIO importer tests. The catalog is built
inline from `_api_album`-shaped fixtures so each test controls
exactly which tracks the importer can see.
"""
from __future__ import annotations

import json
from pathlib import Path

import pytest


# Minimal "valid-ish" MP3 bytes (no ID3 frame). The importer never
# transcodes — it just moves the file + sha256s.
_FAKE_MP3 = b"ID3\x04\x00\x00\x00\x00\x00\x00" + b"\xff\xfb\x90\x00" + b"\x00" * 64


@pytest.fixture
def configured_env(tmp_path, monkeypatch):
    """Fresh data dir + catalog path per test, just like the AIO
    importer fixture. Drops cached `app.*` imports so config.py
    re-reads env vars."""
    data = tmp_path / "data"
    (data / "audio").mkdir(parents=True)
    (data / "import").mkdir(parents=True)
    catalog_path = tmp_path / "ysh_catalog.json"

    monkeypatch.setenv("ODYSSEY_AUTH_TOKEN", "x")
    monkeypatch.setenv("ODYSSEY_DATA_DIR", str(data))
    monkeypatch.setenv("ODYSSEY_YSH_CATALOG_PATH", str(catalog_path))
    import sys
    for name in [k for k in list(sys.modules) if k == "app" or k.startswith("app.")]:
        del sys.modules[name]
    return {"data_dir": data, "catalog_path": catalog_path}


def _write_catalog(path: Path, albums: list[dict]) -> None:
    """Persist a `build_catalog`-shaped JSON file to `path`."""
    payload = {"scrapedAtMs": 1, "albumCount": len(albums), "albums": albums}
    path.write_text(json.dumps(payload))


def _ee11(tracks: list[tuple[int, str, int]] | None = None) -> dict:
    """Exciting Events Vol 11 fixture. order_index is the 0-based
    position; the filename code uses 1-based track numbers
    (order_index 1 → EE-11-02, matching the verified S3 URL for
    Madeleine's Courage)."""
    if tracks is None:
        tracks = [
            (1958, "Madeleine's Courage", 1),
            (1959, "Other EE Story", 2),
        ]
    return {
        "id": 119,
        "title": "Exciting Events - Volume 11",
        "slug": "exciting-events-volume-11",
        "image": "https://s3/EE-11.jpg",
        "code_prefix": "EE-11",
        "lang_code": "en",
        "tracks": [
            {"sku_id": skuid, "title": t, "order_index": idx,
             "code": f"EE-11-{idx+1:02d}"} for (skuid, t, idx) in tracks
        ],
    }


def _bca4(tracks: list[tuple[int, str, int]] | None = None) -> dict:
    if tracks is None:
        tracks = [(800, "The Land of Uz", 1)]
    return {
        "id": 44,
        "title": "Bible Comes Alive - Album 4",
        "slug": "bible-comes-alive-album-4",
        "image": "https://s3/B-4.jpg",
        "code_prefix": "B-4",
        "lang_code": "en",
        "tracks": [
            {"sku_id": skuid, "title": t, "order_index": idx,
             "code": f"B-4-{idx+1:02d}"} for (skuid, t, idx) in tracks
        ],
    }


def _drop(import_dir: Path, name: str) -> Path:
    p = import_dir / name
    p.write_bytes(_FAKE_MP3)
    return p


# =========== parse_filename ============================================


def test_parse_filename_extracts_code_and_title(configured_env):
    from app.import_dropbox_ysh import parse_filename
    code, title = parse_filename("EE-11-02 - Madeleine's Courage.mp3")
    assert code == "EE-11-02"
    assert title == "Madeleine's Courage"


def test_parse_filename_uppercases_code(configured_env):
    from app.import_dropbox_ysh import parse_filename
    code, _ = parse_filename("ee-11-02 - Madeleine's Courage.mp3")
    assert code == "EE-11-02"


def test_parse_filename_replaces_underscores_in_title(configured_env):
    from app.import_dropbox_ysh import parse_filename
    code, title = parse_filename("EE-11-02 - Madeleines_Courage.mp3")
    assert code == "EE-11-02"
    assert title == "Madeleines Courage"


def test_parse_filename_returns_None_for_non_matching_filename(configured_env):
    from app.import_dropbox_ysh import parse_filename
    assert parse_filename("Some Random Title.mp3") == (None, None)
    assert parse_filename("not-yshish.txt") == (None, None)


# =========== resolve_match =============================================


def test_resolve_match_prefers_code_prefix(configured_env):
    from app.import_dropbox_ysh import parse_file, resolve_match
    from app.scrape_ysh import build_indexes
    code_idx, title_idx, _ = build_indexes(
        {"albums": [_ee11(), _bca4([(800, "Madeleine's Courage", 0)])]}
        # Note: BCA4 also has a "Madeleine's Courage" entry — title
        # match would be ambiguous. Code prefix wins.
    )
    drop_dir = configured_env["data_dir"] / "import"
    src = _drop(drop_dir, "EE-11-02 - Madeleine's Courage.mp3")
    match = resolve_match(parse_file(src), code_idx, title_idx)
    assert match is not None
    assert match.album_title == "Exciting Events - Volume 11"
    assert match.sku_id == 1958


def test_resolve_match_falls_back_to_unambiguous_title(configured_env):
    """Filename has no code prefix → title lookup. If the title is
    unique in the catalog, the match succeeds."""
    from app.import_dropbox_ysh import parse_file, resolve_match
    from app.scrape_ysh import build_indexes
    code_idx, title_idx, _ = build_indexes({"albums": [_bca4()]})
    drop_dir = configured_env["data_dir"] / "import"
    src = _drop(drop_dir, "The Land of Uz.mp3")
    match = resolve_match(parse_file(src), code_idx, title_idx)
    assert match is not None
    assert match.sku_id == 800


def test_resolve_match_returns_None_when_title_is_ambiguous(configured_env):
    """Title appears in two albums — importer refuses to guess."""
    from app.import_dropbox_ysh import parse_file, resolve_match
    from app.scrape_ysh import build_indexes
    code_idx, title_idx, _ = build_indexes({"albums": [
        _ee11(tracks=[(1958, "Madeleine's Courage", 2)]),
        _bca4(tracks=[(800, "Madeleine's Courage", 0)]),
    ]})
    drop_dir = configured_env["data_dir"] / "import"
    src = _drop(drop_dir, "Madeleine's Courage.mp3")
    match = resolve_match(parse_file(src), code_idx, title_idx)
    assert match is None


def test_resolve_match_returns_None_for_unknown_file(configured_env):
    from app.import_dropbox_ysh import parse_file, resolve_match
    from app.scrape_ysh import build_indexes
    code_idx, title_idx, _ = build_indexes({"albums": [_ee11()]})
    drop_dir = configured_env["data_dir"] / "import"
    src = _drop(drop_dir, "Not a YSH track.mp3")
    assert resolve_match(parse_file(src), code_idx, title_idx) is None


# =========== run_import (full flow) ====================================


def test_run_import_matched_file_lands_in_ysh_subdir(configured_env):
    from app import config, db
    from app.import_dropbox_ysh import run_import
    db.init()
    _write_catalog(configured_env["catalog_path"], [_ee11()])
    _drop(config.IMPORT_DIR, "EE-11-02 - Madeleine's Courage.mp3")

    summary = run_import()

    assert summary.scanned == 1
    assert summary.imported == 1
    assert summary.unmatched == 0
    # File lives under audio/ysh/<album-slug>/<sku_id>-<title>.mp3
    expected = config.AUDIO_DIR / "ysh" / "exciting-events-volume-11" / "1958-madeleine-s-courage.mp3"
    assert expected.exists()
    # Drop folder no longer has the source.
    assert not (config.IMPORT_DIR / "EE-11-02 - Madeleine's Courage.mp3").exists()
    # DB row inserted with provider_id='ysh', external_id='1958'.
    with db.connect() as c:
        row = c.execute(
            "SELECT provider_id, external_id, title, album, file_path FROM episodes "
            "WHERE provider_id = 'ysh' AND external_id = '1958'"
        ).fetchone()
    assert row is not None
    assert row["title"] == "Madeleine's Courage"
    assert row["album"] == "Exciting Events - Volume 11"
    assert row["file_path"] == str(expected)


def test_run_import_unmatched_file_moves_to_unmatched_dir(configured_env):
    from app import config, db
    from app.import_dropbox_ysh import run_import
    db.init()
    _write_catalog(configured_env["catalog_path"], [_ee11()])
    src = _drop(config.IMPORT_DIR, "Not In Catalog.mp3")

    summary = run_import()

    assert summary.unmatched == 1
    assert summary.imported == 0
    assert not src.exists()
    assert (config.IMPORT_UNMATCHED_DIR / "Not In Catalog.mp3").exists()


def test_run_import_handles_missing_catalog_as_all_unmatched(configured_env):
    from app import config, db
    from app.import_dropbox_ysh import run_import
    db.init()
    # No catalog file written.
    _drop(config.IMPORT_DIR, "EE-11-02 - Madeleine's Courage.mp3")
    summary = run_import()
    # No code/title index → every file is unmatched.
    assert summary.unmatched == 1
    assert summary.imported == 0


def test_run_import_is_idempotent_on_already_imported_sku(configured_env):
    """Re-running on an already-imported SKU moves the duplicate to
    _unmatched/ rather than clobbering the archive."""
    from app import config, db
    from app.import_dropbox_ysh import run_import
    db.init()
    _write_catalog(configured_env["catalog_path"], [_ee11()])
    _drop(config.IMPORT_DIR, "EE-11-02 - Madeleine's Courage.mp3")
    run_import()
    # Second pass on a fresh drop of the same logical file (same
    # filename → same code → same sku).
    _drop(config.IMPORT_DIR, "EE-11-02 - Madeleine's Courage.mp3")
    summary = run_import()
    assert summary.imported == 0
    # The duplicate ends up in _unmatched/ (with a timestamp suffix
    # since the first re-import attempt may have left a file there).
    assert any(config.IMPORT_UNMATCHED_DIR.iterdir())


def test_run_import_skips_already_unmatched_files_on_rerun(configured_env):
    from app import config, db
    from app.import_dropbox_ysh import run_import
    db.init()
    _write_catalog(configured_env["catalog_path"], [_ee11()])
    # Pre-seed an unmatched file directly under _unmatched/.
    config.IMPORT_UNMATCHED_DIR.mkdir(parents=True, exist_ok=True)
    (config.IMPORT_UNMATCHED_DIR / "previously-unmatched.mp3").write_bytes(_FAKE_MP3)

    summary = run_import()

    # Scanner skipped the _unmatched/ subtree, nothing to scan.
    assert summary.scanned == 0


def test_main_returns_nonzero_when_errors_occur(configured_env, monkeypatch):
    """The CLI shim exits 1 if any file errored. Stub run_import to
    return a non-zero error count."""
    import app.import_dropbox_ysh as mod
    monkeypatch.setattr(
        mod, "run_import",
        lambda: mod.YshImportSummary(scanned=1, errors=1),
    )
    assert mod.main() == 1


def test_main_returns_zero_on_clean_run(configured_env, monkeypatch):
    import app.import_dropbox_ysh as mod
    monkeypatch.setattr(
        mod, "run_import",
        lambda: mod.YshImportSummary(scanned=0, imported=0, unmatched=0, errors=0),
    )
    assert mod.main() == 0


def test_resolve_match_id3_fallback_when_filename_has_no_code_or_unique_title(configured_env):
    """When the filename is opaque AND has no code, fall through to
    the ID3 TIT2 tag and try the title there. Mocks the ID3 reader
    so we don't have to embed real ID3 frames in a test mp3."""
    from app.import_dropbox_ysh import ParsedYshFile, resolve_match
    from app.scrape_ysh import build_indexes
    code_idx, title_idx, _ = build_indexes({"albums": [_bca4()]})

    drop_dir = configured_env["data_dir"] / "import"
    src = _drop(drop_dir, "01.mp3")        # opaque filename
    parsed = ParsedYshFile(path=src, code=None, title="01")   # not a real title

    import app.import_dropbox_ysh as mod
    import unittest.mock as um
    with um.patch.object(mod, "_read_id3_title", return_value="The Land of Uz"):
        match = resolve_match(parsed, code_idx, title_idx)
    assert match is not None
    assert match.album_title == "Bible Comes Alive - Album 4"


def test_run_import_catches_per_file_errors_and_continues(configured_env, monkeypatch):
    """A per-file exception during _import_one must not abort the
    whole batch — the summary records the error and the loop moves
    on to the next file."""
    from app import config, db
    from app.import_dropbox_ysh import run_import
    db.init()
    _write_catalog(configured_env["catalog_path"], [_ee11()])
    _drop(config.IMPORT_DIR, "EE-11-02 - Madeleine's Courage.mp3")
    _drop(config.IMPORT_DIR, "EE-11-03 - Other EE Story.mp3")

    # Sabotage the first file's import path. The simplest reliable
    # blow-up is patching shutil.move so the first call raises.
    import shutil
    real_move = shutil.move
    calls = {"n": 0}
    def flaky(src, dst):
        calls["n"] += 1
        if calls["n"] == 1:
            raise OSError("boom")
        return real_move(src, dst)
    monkeypatch.setattr(shutil, "move", flaky)

    summary = run_import()
    assert summary.errors == 1
    # Second file still imported despite the first one erroring.
    assert summary.imported == 1
