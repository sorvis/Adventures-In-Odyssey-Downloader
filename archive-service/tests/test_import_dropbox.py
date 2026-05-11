"""
Drop-folder importer tests. Cover the pure-helper layers (title
normalization, catalog index build, filename parsing) and the
end-to-end behavior (drop → match → moved + DB row added; or →
unmatched directory).

Mutagen is in requirements but the test mp3 fixture has no real ID3
title — these tests verify the filename-fallback path. The ID3 path
is exercised indirectly because `_read_id3` returns None on the
synthetic bytes, and the code falls through to filename parsing
correctly.
"""
from __future__ import annotations
import json
import sys
import sqlite3
from pathlib import Path

import pytest


@pytest.fixture(autouse=True)
def configured_env(tmp_path: Path, monkeypatch):
    """Point app config at a fresh data + catalog under tmp_path,
    drop cached app imports so config.py re-evaluates."""
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    catalog_path = tmp_path / "aio_catalog.json"

    catalog = {
        "scrapedAtMs": 0,
        "albumCount": 1,
        "albums": [
            {
                "albumNumber": "20",
                "name": "#20: A Journey of Choices",
                "imageUrl": None,
                "episodes": [
                    {"name": "Afraid, Not!",          "shortName": "#261: Afraid, Not!"},
                    {"name": "When Bad Isn't So Good","shortName": "#263: When Bad Isn't So Good"},
                    {"name": "Making the Grade",      "shortName": "#264: Making the Grade"},
                ],
            },
            {
                "albumNumber": "04",
                "name": "Camp What-A-Nut",
                "imageUrl": None,
                "episodes": [
                    # Multi-part — matches the trickier title-variants logic.
                    {"name": "Camp What-a-Nut, Part 1 of 2", "shortName": "#037: Camp What-a-Nut, Part 1 of 2"},
                    {"name": "Camp What-a-Nut, Part 2 of 2", "shortName": "#038: Camp What-a-Nut, Part 2 of 2"},
                    # & vs 'and' across the catalog/filename boundary.
                    {"name": "Bernard and Esther, Part 1 of 2", "shortName": "#165: Bernard and Esther, Part 1 of 2"},
                ],
            },
            {
                "albumNumber": "FP",
                "name": "Family Portraits",
                "imageUrl": None,
                "episodes": [
                    # Episode without a broadcast number — exercises the hash fallback.
                    {"name": "A Bonus Track", "shortName": ""},
                ],
            },
        ],
    }
    catalog_path.write_text(json.dumps(catalog), encoding="utf-8")

    monkeypatch.setenv("ODYSSEY_AUTH_TOKEN", "testtoken")
    monkeypatch.setenv("ODYSSEY_DATA_DIR", str(data_dir))
    for name in [k for k in sys.modules.keys() if k == "app" or k.startswith("app.")]:
        del sys.modules[name]

    # Patch CATALOG_PATH after import so import_dropbox uses our fake.
    import app.config
    app.config.CATALOG_PATH = catalog_path
    import app.import_dropbox
    app.import_dropbox.CATALOG_PATH = catalog_path

    yield {"data_dir": data_dir, "catalog_path": catalog_path}


def _tiny_mp3(path: Path) -> None:
    path.write_bytes(b"ID3\x04\x00\x00\x00\x00\x00\x00" + b"\xff\xfb\x90\x00" + b"\x00" * 64)


# ----- pure helpers ---------------------------------------------------

def test_normalize_title_strips_quotes_and_number_prefix():
    from app.import_dropbox import normalize_title
    assert normalize_title("#657: Clutter") == "clutter"
    assert normalize_title("  Clutter  ") == "clutter"
    assert normalize_title("“Clutter”") == "clutter"
    assert normalize_title("#  657  :  CLUTTER ") == "clutter"


def test_normalize_title_collapses_internal_whitespace():
    from app.import_dropbox import normalize_title
    assert normalize_title("War   of\tthe  Words") == "war of the words"


def test_normalize_title_empty_input():
    from app.import_dropbox import normalize_title
    assert normalize_title("") == ""
    assert normalize_title("   ") == ""


def test_normalize_title_drops_punctuation_and_maps_ampersand_to_and():
    from app.import_dropbox import normalize_title
    # Real-world examples from the C# back-catalog the user dumped:
    assert normalize_title("Bernard & Esther 1") == "bernard and esther 1"
    assert normalize_title("Forever…Amen") == "forever amen"
    assert normalize_title("Forever. . .Amen") == "forever amen"
    assert normalize_title("Truth, Trivia & 'Trina") == "truth trivia and trina"
    assert normalize_title("Camp What-A-Nut") == "camp what a nut"
    # Curly quotes + parens.
    assert normalize_title("“Quotes (yes)”") == "quotes yes"


def test_title_variants_expands_part_suffix():
    from app.import_dropbox import _title_variants
    # Catalog form — ", Part N of M" → also indexes bare "<stem> N"
    # and "<stem> part N" so user filenames can match.
    variants = _title_variants("Camp What-a-Nut, Part 1 of 2")
    assert "camp what a nut part 1 of 2" in variants
    assert "camp what a nut 1" in variants
    assert "camp what a nut part 1" in variants


def test_title_variants_passes_through_when_no_part_suffix():
    from app.import_dropbox import _title_variants
    variants = _title_variants("Clutter")
    assert variants == ["clutter"]


def test_title_variants_handles_paren_form_and_extra_spaces():
    from app.import_dropbox import _title_variants
    variants = _title_variants("Face the Future (Part 1 of 3)")
    assert "face the future 1" in variants


def test_filename_pattern_accepts_8_digit_ids():
    from app.import_dropbox import _title_from_filename
    from pathlib import Path
    f = Path("/tmp/20120201#-The_Devil_Made_Me_Do_It.mp3")
    f.touch()
    assert _title_from_filename(f) == "The Devil Made Me Do It"


def test_build_title_index_matches_both_name_and_short_name(configured_env):
    from app.import_dropbox import build_title_index, load_catalog, normalize_title
    catalog = load_catalog()
    index = build_title_index(catalog)
    # Long name shape (catalog `name`):
    assert index[normalize_title("Afraid, Not!")].album == "#20: A Journey of Choices"
    # Short-name shape (the user might drop a file titled "#261: Afraid, Not!"):
    assert index[normalize_title("#261: Afraid, Not!")].album == "#20: A Journey of Choices"
    # Broadcast number is parsed out of the shortName:
    assert index[normalize_title("Afraid, Not!")].broadcast_number == 261


def test_title_from_filename_handles_id_prefix(configured_env, tmp_path: Path):
    from app.import_dropbox import _title_from_filename
    f = tmp_path / "1234-Some Episode Title.mp3"
    f.write_bytes(b"")
    assert _title_from_filename(f) == "Some Episode Title"


def test_title_from_filename_handles_underscore_separator(configured_env, tmp_path: Path):
    from app.import_dropbox import _title_from_filename
    f = tmp_path / "1234_Some_Episode_Title.mp3"
    f.write_bytes(b"")
    assert _title_from_filename(f) == "Some Episode Title"


def test_title_from_filename_bare_title(configured_env, tmp_path: Path):
    from app.import_dropbox import _title_from_filename
    f = tmp_path / "Some Episode Title.mp3"
    f.write_bytes(b"")
    assert _title_from_filename(f) == "Some Episode Title"


def test_title_from_filename_hash_dash_separator(configured_env, tmp_path: Path):
    """The actual C# tool format the user dumped: 'NNN#-Title_With_Underscores.mp3'."""
    from app.import_dropbox import _title_from_filename
    f = tmp_path / "671#-Fast_as_I_Can.mp3"
    f.write_bytes(b"")
    assert _title_from_filename(f) == "Fast as I Can"

    f2 = tmp_path / "727339#-Between_the_Lines,_Part_1.mp3"
    f2.write_bytes(b"")
    assert _title_from_filename(f2) == "Between the Lines, Part 1"


def test_filename_title_beats_useless_id3(configured_env):
    """Real-world: C# files have filename='671#-Fast_as_I_Can.mp3' but
    ID3 TIT2='Adventures in Odyssey 08/27/2011'. Filename must win so
    the catalog match works."""
    pytest.importorskip("mutagen")
    from mutagen.id3 import ID3, ID3NoHeaderError, TIT2

    from app import config, db
    from app.import_dropbox import run_import

    db.init()
    drop = config.IMPORT_DIR
    # Pick a title we know is in our test catalog.
    src = drop / "261#-Afraid,_Not!.mp3"
    _tiny_mp3(src)
    try:
        tags = ID3(src)
    except ID3NoHeaderError:
        tags = ID3()
    tags["TIT2"] = TIT2(encoding=3, text="Adventures in Odyssey 08/27/2011")
    tags.save(src)

    summary = run_import(drop)
    assert summary.imported == 1, summary.samples
    # The catalog matched on the filename's "Afraid, Not!", not the
    # date string from ID3.
    assert (config.AUDIO_DIR / "aio" / "20-a-journey-of-choices" / "261-afraid-not.mp3").exists()


# ----- end-to-end -----------------------------------------------------

def test_run_import_matched_file_lands_in_album_folder(configured_env):
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "9999-Afraid, Not!.mp3"
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.scanned == 1
    assert summary.imported == 1
    assert summary.unmatched == 0
    assert summary.errors == 0

    # Source file must be gone from the drop folder.
    assert not src.exists()

    # Target file must exist under audio/<album-slug>/<id>-<title-slug>.mp3.
    expected_album_dir = config.AUDIO_DIR / "aio" / "20-a-journey-of-choices"
    assert expected_album_dir.is_dir()
    files = list(expected_album_dir.iterdir())
    assert len(files) == 1
    target = files[0]
    # Episode id should be the broadcast number (261), title slug 'afraid-not'.
    assert target.name == "261-afraid-not.mp3"

    # Episodes row should exist with the right shape.
    with sqlite3.connect(config.DB_PATH) as c:
        row = c.execute(
            "SELECT episode_id, album, title, file_path FROM episodes"
        ).fetchone()
    assert row[0] == 261
    assert row[1] == "#20: A Journey of Choices"
    assert row[2] == "Afraid, Not!"
    assert row[3] == str(target)


def test_run_import_unmatched_file_moves_to_unmatched(configured_env):
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "Some Random Sermon From Another Show.mp3"
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.scanned == 1
    assert summary.imported == 0
    assert summary.unmatched == 1

    # Source moved to the unmatched dir.
    assert not src.exists()
    assert (config.IMPORT_UNMATCHED_DIR / src.name).exists()

    # No DB row should have been added.
    with sqlite3.connect(config.DB_PATH) as c:
        count = c.execute("SELECT COUNT(*) FROM episodes").fetchone()[0]
    assert count == 0


def test_run_import_skips_files_already_in_unmatched(configured_env):
    """Re-running the importer on a tree that already has an unmatched
    pile under _unmatched/ must NOT re-process those files (would
    create an infinite re-shuffle loop)."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    pre = config.IMPORT_UNMATCHED_DIR
    pre.mkdir(parents=True, exist_ok=True)
    _tiny_mp3(pre / "old-rejected.mp3")

    summary = run_import(config.IMPORT_DIR)
    # The file in _unmatched/ is silently ignored — scanned must be 0.
    assert summary.scanned == 0


def test_run_import_is_idempotent(configured_env):
    """Re-dropping the same matched file should re-import cleanly
    (INSERT OR REPLACE on the same id)."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "Afraid, Not!.mp3"
    _tiny_mp3(src)

    s1 = run_import(drop)
    assert s1.imported == 1

    # Re-drop the same logical file (after the first run moved it).
    src2 = drop / "Afraid, Not!.mp3"
    _tiny_mp3(src2)
    s2 = run_import(drop)
    assert s2.imported == 1
    assert s2.errors == 0

    with sqlite3.connect(config.DB_PATH) as c:
        # Still exactly one row for episode_id=261 — no duplicates.
        rows = c.execute("SELECT episode_id FROM episodes").fetchall()
    assert rows == [(261,)]


def test_run_import_on_missing_root_returns_clean_empty_summary(configured_env, tmp_path: Path):
    from app.import_dropbox import run_import
    nonexistent = tmp_path / "does-not-exist"
    summary = run_import(nonexistent)
    assert summary.scanned == 0
    assert summary.imported == 0
    assert summary.unmatched == 0
    assert summary.errors == 0


def test_run_import_records_per_file_errors_without_aborting(configured_env, monkeypatch):
    """When parse_file blows up on one file, the loop must keep going
    and surface the error in the summary instead of crashing."""
    from app import config, db
    import app.import_dropbox as impmod

    db.init()
    drop = config.IMPORT_DIR
    bad = drop / "Afraid, Not!.mp3"
    good = drop / "Making the Grade.mp3"
    _tiny_mp3(bad)
    _tiny_mp3(good)

    real_parse = impmod.parse_file

    def flaky_parse(path):
        if path.name.startswith("Afraid"):
            raise RuntimeError("synthetic parse failure")
        return real_parse(path)

    monkeypatch.setattr(impmod, "parse_file", flaky_parse)

    summary = impmod.run_import(drop)
    assert summary.scanned == 2
    assert summary.errors == 1
    assert summary.imported == 1
    # The good file must still have been processed.
    assert (config.AUDIO_DIR / "aio" / "20-a-journey-of-choices" / "264-making-the-grade.mp3").exists()


def test_main_cli_returns_zero_on_clean_run(configured_env, capsys, monkeypatch):
    """The `python -m app.import_dropbox` entry point — exercises arg
    parsing, db.init(), run_import, and the printed summary."""
    from app import config
    import app.import_dropbox as impmod

    src = config.IMPORT_DIR
    src.mkdir(parents=True, exist_ok=True)
    _tiny_mp3(src / "Clutter from another album.mp3")  # unmatched: catalog has Afraid/Making/etc only

    monkeypatch.setattr("sys.argv", ["import_dropbox"])
    rc = impmod.main()
    assert rc == 0
    out = capsys.readouterr().out
    assert "Scanned   : 1" in out
    assert "Unmatched : 1" in out


def test_id3_title_is_preferred_over_filename(configured_env):
    """Writing a real TIT2 tag onto a file should override the filename
    heuristic — the user might have a file named '12345.mp3' but tagged
    with a real episode title."""
    pytest.importorskip("mutagen")
    from mutagen.id3 import ID3, ID3NoHeaderError, TIT2

    from app import config, db
    from app.import_dropbox import run_import

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "garbage-filename.mp3"
    _tiny_mp3(src)
    # Write a TIT2 tag claiming this is "Afraid, Not!".
    try:
        tags = ID3(src)
    except ID3NoHeaderError:
        tags = ID3()
    tags["TIT2"] = TIT2(encoding=3, text="Afraid, Not!")
    tags.save(src)

    summary = run_import(drop)
    assert summary.imported == 1
    # ID3 title beat the filename; row landed in the right album.
    assert (config.AUDIO_DIR / "aio" / "20-a-journey-of-choices" / "261-afraid-not.mp3").exists()


def test_run_import_matches_part_suffix_filename_against_catalog_part_of_M(configured_env):
    """The big real-world category: C# files are 'Camp_What-A-Nut_1.mp3'
    while the catalog title is 'Camp What-a-Nut, Part 1 of 2'. The
    title-variants expansion should bridge the gap."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "037#-Camp_What-A-Nut_1.mp3"
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.imported == 1, summary.samples
    # Episode_id should be the canonical broadcast number 37 from the
    # catalog's shortName, NOT the filename's prefix (also 37 here,
    # but the principle is "catalog wins").
    assert (config.AUDIO_DIR / "aio" / "camp-what-a-nut" / "37-camp-what-a-nut-part-1-of-2.mp3").exists()


def test_run_import_maps_ampersand_to_and(configured_env):
    """User filename 'Bernard_&_Esther_1.mp3' should match the
    catalog's 'Bernard and Esther, Part 1 of 2'."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "165#-Bernard_&_Esther_1.mp3"
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.imported == 1, summary.samples


def test_run_import_falls_back_to_filename_id_when_title_is_typoed(configured_env):
    """Real-world rescue: '263#-When_Bad_Isnt_So_Good.mp3' has a
    typo'd title (missing apostrophe) but the filename's id IS in
    the catalog as #263. The broadcast-number fallback should match."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    # Title would normalize to 'when bad isnt so good' which doesn't
    # match catalog's "When Bad Isn't So Good" (apostrophe gets
    # dropped on both sides — actually this WOULD match. Pick a
    # pathological case: a deliberate misspelling.
    src = drop / "263#-When_Bad_isnt_So_Goood.mp3"  # extra 'o'
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.imported == 1, summary.samples
    # The file should have landed under album 20 — the catalog's
    # canonical title (not the typoed filename) wins.
    assert (config.AUDIO_DIR / "aio" / "20-a-journey-of-choices" / "263-when-bad-isn-t-so-good.mp3").exists()


def test_run_import_filename_id_fallback_uses_canonical_catalog_title(configured_env):
    """Broadcast-number rescue must use the catalog's canonical
    title, NOT the typoed filename — otherwise the on-disk file
    name would carry the typo forward forever."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "264#-Makin_the_Grad.mp3"  # both words abbreviated
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.imported == 1
    assert (config.AUDIO_DIR / "aio" / "20-a-journey-of-choices" / "264-making-the-grade.mp3").exists()


def test_run_import_skips_pure_date_filenames_as_unmatched(configured_env):
    """C# files with no real title beyond the broadcast date can't
    possibly match the catalog. Ensure they land in _unmatched/
    cleanly without errors."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "00 Adventures in Odyssey 02_17_20.mp3"
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.scanned == 1
    assert summary.imported == 0
    assert summary.unmatched == 1
    assert (config.IMPORT_UNMATCHED_DIR / src.name).exists()


def test_run_import_synthetic_id_for_episode_without_broadcast_number(configured_env):
    """The 'A Bonus Track' catalog entry has no shortName, so the
    importer falls back to a hash-based stable synthetic episode_id."""
    from app.import_dropbox import run_import
    from app import config, db

    db.init()
    drop = config.IMPORT_DIR
    src = drop / "A Bonus Track.mp3"
    _tiny_mp3(src)

    summary = run_import(drop)
    assert summary.imported == 1

    with sqlite3.connect(config.DB_PATH) as c:
        ep_id = c.execute("SELECT episode_id FROM episodes").fetchone()[0]
    # Synthetic ids come from a 7-hex-digit MD5 prefix → non-zero,
    # well below 2^28.
    assert 0 < ep_id < (1 << 28)
