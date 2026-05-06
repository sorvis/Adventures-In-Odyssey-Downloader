"""
Drop-folder importer for the user's existing pile of MP3s.

Workflow:
  1. User SCPs / NFS-copies arbitrary audio files into /data/import/
     (filenames can be inconsistent — anything goes).
  2. User runs `scripts/run-import.sh` on the LXC.
  3. This module walks /data/import/, derives a title for each file
     (ID3 TIT2 tag, then filename heuristics), looks it up in the
     bundled AIO catalog, and:
        - On match: moves the file into /data/audio/<album-slug>/
          with a canonical filename, upserts an episodes row.
        - No match: moves the file into /data/import/_unmatched/ so
          the user can rename + re-drop.

Idempotent: re-running on the same dropped files is a no-op (same
target path, INSERT OR REPLACE on the same episode_id). Episode_id
for matched files is the canonical AIO broadcast number from the
catalog (e.g. 657 for "Clutter") — that means a fresh oneplace
download (which uses CMS ids in the 1.27M range) and an imported
file may end up as two rows for the same episode. Acceptable for
v1; a title-based dedupe pass can land later if duplicates pile up.
"""
from __future__ import annotations

import json
import logging
import re
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from . import db
from .config import (
    AUDIO_DIR,
    CATALOG_PATH,
    IMPORT_DIR,
    IMPORT_UNMATCHED_DIR,
)
from .storage import episode_path, slugify

log = logging.getLogger(__name__)

# Filename patterns we know how to parse — most-specific first. Same
# heuristics as the client-side scripts/import-audio-dir.py so a file
# that flowed through that path also flows through this one.
_FILENAME_PATTERNS = [
    # "1234-Episode Title.mp3" — original C# downloader format
    re.compile(r"^(?P<id>\d{2,7})\s*[-_]\s*(?P<title>.+?)\.(?:mp3|m4a)$", re.IGNORECASE),
    # "Episode Title (1234).mp3"
    re.compile(r"^(?P<title>.+?)\s*\((?P<id>\d{2,7})\)\.(?:mp3|m4a)$", re.IGNORECASE),
    # bare "Episode Title.mp3"
    re.compile(r"^(?P<title>.+?)\.(?:mp3|m4a)$", re.IGNORECASE),
]

_NUMBER_PREFIX = re.compile(r"^\s*#\s*(\d+)(?:[\.½⅓⅔]\d*)?\s*:?\s*", re.UNICODE)
_BROADCAST_NUMBER = re.compile(r"#\s*(\d+)")


# ----- catalog matching --------------------------------------------------

@dataclass(frozen=True)
class CatalogMatch:
    album: str
    canonical_title: str
    broadcast_number: Optional[int]


def normalize_title(raw: str) -> str:
    """Lowercase, strip curly + straight quotes, strip leading "#NNN: ",
    collapse whitespace. Mirrors AioCatalogMatch.kt:normalizeTitle."""
    if not raw or not raw.strip():
        return ""
    s = raw.replace("“", '"').replace("”", '"')
    s = s.replace("‘", "'").replace("’", "'")
    s = s.strip(" \t\"'")
    s = _NUMBER_PREFIX.sub("", s)
    s = s.lower()
    s = re.sub(r"\s+", " ", s).strip()
    return s


def _strip_number_prefix(s: str) -> str:
    """'#657: Clutter' → 'Clutter'."""
    return _NUMBER_PREFIX.sub("", s, count=1)


def _broadcast_number(short_name: str) -> Optional[int]:
    m = _BROADCAST_NUMBER.search(short_name or "")
    return int(m.group(1)) if m else None


def load_catalog(catalog_path: Path = CATALOG_PATH) -> dict:
    with catalog_path.open("r", encoding="utf-8") as f:
        return json.load(f)


def build_title_index(catalog: dict) -> dict[str, CatalogMatch]:
    """Pre-bucket every catalog episode by normalized title for O(1)
    lookup. Both the long `name` and the prefix-stripped `shortName`
    are indexed so different title shapes from old MP3 filenames still
    resolve."""
    index: dict[str, CatalogMatch] = {}
    for album in catalog.get("albums", []):
        album_name = album.get("name") or ""
        if not album_name:
            continue
        for ep in album.get("episodes", []):
            name = (ep.get("name") or "").strip()
            short = (ep.get("shortName") or "").strip()
            canonical = name or short
            broadcast = _broadcast_number(short)
            match = CatalogMatch(album=album_name, canonical_title=canonical,
                                 broadcast_number=broadcast)
            for raw in (name, _strip_number_prefix(short)):
                key = normalize_title(raw)
                if key:
                    index.setdefault(key, match)
    return index


# ----- file metadata -----------------------------------------------------

@dataclass
class ParsedFile:
    path: Path
    title: str          # best-effort title — never empty
    duration_ms: int
    air_date: Optional[str]


def _read_id3(path: Path) -> tuple[Optional[str], Optional[str], int]:
    """ID3 title + air-date frame + duration in ms. Returns
    (None, None, 0) on any failure or missing metadata."""
    try:
        from mutagen.id3 import ID3, ID3NoHeaderError  # type: ignore
        from mutagen.mp3 import MP3  # type: ignore
    except ImportError:
        return None, None, 0
    title: Optional[str] = None
    air_date: Optional[str] = None
    dur_ms = 0
    try:
        try:
            tags = ID3(path)
            if "TIT2" in tags:
                title = str(tags["TIT2"]).strip() or None
            for k in ("TDRC", "TDOR", "TYER"):
                if k in tags:
                    val = str(tags[k]).strip()
                    if val:
                        air_date = val
                        break
        except ID3NoHeaderError:
            pass
        try:
            audio = MP3(path)
            if audio.info and audio.info.length:
                dur_ms = int(audio.info.length * 1000)
        except Exception:
            pass
    except Exception:
        pass
    return title, air_date, dur_ms


def _title_from_filename(path: Path) -> str:
    name = path.name
    for pat in _FILENAME_PATTERNS:
        m = pat.match(name)
        if m:
            return m.group("title").strip().replace("_", " ")
    return path.stem


def parse_file(path: Path) -> ParsedFile:
    id3_title, air_date, dur_ms = _read_id3(path)
    title = id3_title or _title_from_filename(path)
    return ParsedFile(path=path, title=title, duration_ms=dur_ms, air_date=air_date)


# ----- main entry --------------------------------------------------------

@dataclass
class ImportSummary:
    scanned: int = 0
    imported: int = 0
    unmatched: int = 0
    errors: int = 0
    samples: list[str] | None = None  # human-readable per-file lines

    def __post_init__(self):
        if self.samples is None:
            self.samples = []


def _candidate_files(import_root: Path) -> list[Path]:
    out: list[Path] = []
    for path in sorted(import_root.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in (".mp3", ".m4a"):
            continue
        # Skip files already in _unmatched (re-run safety) and any hidden.
        try:
            path.relative_to(IMPORT_UNMATCHED_DIR)
            continue
        except ValueError:
            pass
        if path.name.startswith("."):
            continue
        out.append(path)
    return out


def _import_one(parsed: ParsedFile, index: dict[str, CatalogMatch]) -> tuple[bool, str]:
    """Try to import a single file. Returns (matched, summary_line)."""
    key = normalize_title(parsed.title)
    match = index.get(key) if key else None
    if not match:
        # Move to _unmatched/ with original filename — preserve so the
        # user can rename + re-drop later.
        IMPORT_UNMATCHED_DIR.mkdir(parents=True, exist_ok=True)
        dst = IMPORT_UNMATCHED_DIR / parsed.path.name
        if dst.exists():
            dst = IMPORT_UNMATCHED_DIR / f"{dst.stem}-{parsed.path.stat().st_mtime_ns}{dst.suffix}"
        shutil.move(str(parsed.path), str(dst))
        return False, f"UNMATCHED  {parsed.path.name!r}  title={parsed.title!r}"

    # Episode-id strategy: prefer the AIO broadcast number from
    # shortName (e.g. 657). Fall back to a 7-hex-digit hash of the
    # canonical title when the catalog row has no number — a stable
    # deterministic synthetic id.
    if match.broadcast_number is not None:
        episode_id = match.broadcast_number
    else:
        import hashlib
        episode_id = int(
            hashlib.md5(match.canonical_title.encode("utf-8")).hexdigest()[:7], 16
        )

    target = episode_path(episode_id, match.canonical_title, match.album)
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.resolve() != parsed.path.resolve():
        # Same id, different path — overwrite with the new file.
        target.unlink()
    if parsed.path.resolve() != target.resolve():
        shutil.move(str(parsed.path), str(target))

    duration_secs = max(parsed.duration_ms // 1000, 0)
    file_size = target.stat().st_size

    with db.connect() as c:
        c.execute(
            """INSERT OR REPLACE INTO episodes
               (episode_id, title, air_date, album, description,
                duration_secs, file_path, file_size, sha256, source_url)
               VALUES (?, ?, ?, ?, NULL, ?, ?, ?, NULL, NULL)""",
            (
                episode_id, match.canonical_title, parsed.air_date, match.album,
                duration_secs, str(target), file_size,
            ),
        )
    return True, f"IMPORTED   ep={episode_id} album={match.album!r} title={match.canonical_title!r}"


def run_import(import_root: Path = IMPORT_DIR) -> ImportSummary:
    """Top-level entry. Walks `import_root`, processes every audio
    file, returns a summary of what happened."""
    summary = ImportSummary()
    if not import_root.exists():
        log.warning("import root %s does not exist — nothing to do", import_root)
        return summary
    catalog = load_catalog()
    index = build_title_index(catalog)
    files = _candidate_files(import_root)
    summary.scanned = len(files)
    for f in files:
        try:
            parsed = parse_file(f)
            matched, line = _import_one(parsed, index)
            assert summary.samples is not None
            summary.samples.append(line)
            if matched:
                summary.imported += 1
            else:
                summary.unmatched += 1
        except Exception as e:
            summary.errors += 1
            assert summary.samples is not None
            summary.samples.append(f"ERROR      {f.name!r}: {e}")
            log.exception("import_one failed for %s", f)
    return summary


def main() -> int:
    """CLI entry. Run from inside the container:
        python -m app.import_dropbox
    The /data/import root is implied; pass a different one with
    --root /path/to/dir.
    """
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", type=Path, default=IMPORT_DIR,
                    help="directory to scan (default /data/import)")
    args = ap.parse_args()

    db.init()
    summary = run_import(args.root)
    print(f"Scanned   : {summary.scanned}")
    print(f"Imported  : {summary.imported}")
    print(f"Unmatched : {summary.unmatched}  (moved to {IMPORT_UNMATCHED_DIR})")
    print(f"Errors    : {summary.errors}")
    print()
    assert summary.samples is not None
    for line in summary.samples:
        print(line)
    return 0 if summary.errors == 0 else 1


if __name__ == "__main__":
    import sys
    sys.exit(main())
