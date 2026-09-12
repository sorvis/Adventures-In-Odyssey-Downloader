import sqlite3
from contextlib import contextmanager
from .config import DB_PATH, AUDIO_DIR, IMPORT_DIR, IMPORT_UNMATCHED_DIR

SCHEMA = """
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
    archived_at    TEXT NOT NULL DEFAULT (datetime('now')),
    -- Stamped by scripts/whisper_titles.py whenever a row has been
    -- whisper-checked (regardless of whether the check produced a
    -- title change). NULL = never validated; lets re-runs skip rows
    -- that are already confirmed.
    title_validated_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_episodes_air_date ON episodes(air_date);
CREATE INDEX IF NOT EXISTS idx_episodes_album    ON episodes(album);
CREATE INDEX IF NOT EXISTS idx_episodes_title    ON episodes(title COLLATE NOCASE);

-- Cache of whisperx clip transcriptions, keyed by everything a
-- transcript actually depends on. Transcribing is the expensive step
-- in the whisper-titles pipeline (GPU minutes per episode); the
-- matcher that consumes a transcript is cheap and changes often. So
-- transcripts are cached permanently and re-scored offline, rather
-- than re-transcribed whenever the matcher moves.
--
-- The key is deliberately wide. A transcript of the first 90 seconds
-- is NOT a valid answer for a caller asking about the first 180, and a
-- transcript from one whisper model is not interchangeable with
-- another's. Narrowing this to (episode_id, segment) would let the
-- cache return a confidently wrong clip. `audio_sha256` invalidates
-- the entry when an episode's file is re-ingested; it stores '' (not
-- NULL) when unknown, because SQLite permits NULLs in a PRIMARY KEY
-- and they would silently defeat uniqueness.
CREATE TABLE IF NOT EXISTS episode_transcripts (
    episode_id   INTEGER NOT NULL,
    segment      TEXT    NOT NULL,          -- 'head' | 'tail'
    secs         INTEGER NOT NULL,          -- clip length, seconds
    model        TEXT    NOT NULL,          -- e.g. 'large-v3'
    audio_sha256 TEXT    NOT NULL DEFAULT '',
    text         TEXT    NOT NULL,
    created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (episode_id, segment, secs, model, audio_sha256),
    FOREIGN KEY (episode_id) REFERENCES episodes(episode_id) ON DELETE CASCADE
) WITHOUT ROWID;

CREATE TABLE IF NOT EXISTS album_cache (
    title_key   TEXT PRIMARY KEY,
    album       TEXT,
    looked_up_at TEXT NOT NULL DEFAULT (datetime('now'))
);
"""


def init() -> None:
    AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    IMPORT_DIR.mkdir(parents=True, exist_ok=True)
    IMPORT_UNMATCHED_DIR.mkdir(parents=True, exist_ok=True)
    with connect() as c:
        c.executescript(SCHEMA)
        _migrate_schema(c)


def _migrate_schema(c: sqlite3.Connection) -> None:
    """v1 → v2 schema migration. Adds the multi-show columns to the
    `episodes` table — kept in the migrator (not the inline CREATE
    above) so that legacy installs whose first contact with the new
    server is this exact path land in the same end state as fresh
    installs. Idempotent: skips columns that already exist.

      provider_id: which show this episode came from ("aio" today,
                   "ysh" once the new client routes land).
      external_id: stable id within the provider — oneplace CMS id
                   stringified for AIO, sku_id stringified for YSH.
                   Stays nullable on legacy rows until the backfill
                   below; new inserts always populate it.
    """
    cols = {row["name"] for row in c.execute("PRAGMA table_info(episodes)")}
    if "provider_id" not in cols:
        c.execute("ALTER TABLE episodes ADD COLUMN provider_id TEXT NOT NULL DEFAULT 'aio'")
    if "external_id" not in cols:
        c.execute("ALTER TABLE episodes ADD COLUMN external_id TEXT")
    if "title_validated_at" not in cols:
        # New column for scripts/whisper_titles.py. Nullable on
        # legacy rows; populated as the whisper-titles pipeline
        # walks the archive.
        c.execute("ALTER TABLE episodes ADD COLUMN title_validated_at TEXT")
    if "title_validator_version" not in cols:
        # Which matcher produced the validation, e.g. "aio/1", "ysh/2".
        # A bare timestamp can't answer "was this checked by the CURRENT
        # matcher?", and that gap already bit: 376 rows stamped
        # 2026-06-08 were skipped by every later run even though the
        # matcher was hardened on 2026-07-13. Versioned per provider so
        # improving one show's matcher doesn't re-queue the other's.
        # NULL = validated before versioning existed (treat as stale).
        c.execute("ALTER TABLE episodes ADD COLUMN title_validator_version TEXT")
    # Backfill external_id for any pre-migration row that's still
    # NULL — stringify the legacy episode_id. Cheap; runs only when
    # there are unmigrated rows.
    c.execute(
        "UPDATE episodes SET external_id = CAST(episode_id AS TEXT) "
        "WHERE external_id IS NULL"
    )
    c.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_episodes_provider_external "
        "ON episodes(provider_id, external_id) WHERE external_id IS NOT NULL"
    )


@contextmanager
def connect():
    conn = sqlite3.connect(DB_PATH, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        yield conn
    finally:
        conn.close()
