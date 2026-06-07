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
