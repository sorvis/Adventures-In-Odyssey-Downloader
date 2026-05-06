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
    archived_at    TEXT NOT NULL DEFAULT (datetime('now'))
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
