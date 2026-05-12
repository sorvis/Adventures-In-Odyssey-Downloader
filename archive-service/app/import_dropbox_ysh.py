"""
YSH drop-folder importer (step 13). Mirrors `import_dropbox.py` but
matches against the YSH album catalog (built by `scrape_ysh.py`).

Filename convention (verified from yourstoryhour.org's S3 URLs):

    EE-11-02 - Madeleine's Courage.mp3
    GS-07-05 - Eli Whitney - Boy Craftsman.mp3
    B-4-02 - The Land of Uz.mp3
    A-08-19 - What Joe Learned.mp3

Structure: ``<CODE>-<VOL>-<TRACK> - <Title>.mp3``. The code prefix
(``EE-11`` etc.) uniquely identifies the album in the catalog, which
disambiguates the ~65 titles that appear in more than one album.

Match order, highest-confidence first:

1. Filename **code prefix** → ``code_index`` (e.g. ``EE-11-02``).
2. Filename **title** → ``title_index`` (single match only;
   ambiguous = N>1 hits, falls through to step 3).
3. **ID3 TIT2** tag → ``title_index`` (single match only).
4. Miss → ``/data/import/_unmatched/``.

On match the file is moved to
``<AUDIO_DIR>/ysh/<album-slug>/<sku_id>-<title-slug>.mp3`` and an
``episodes`` row is inserted with ``provider_id='ysh'`` and
``external_id=str(sku_id)`` — same shape the YSH Android client
uploads.
"""
from __future__ import annotations

import hashlib
import logging
import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from . import db
from .config import AUDIO_DIR, IMPORT_DIR, IMPORT_UNMATCHED_DIR, YSH_CATALOG_PATH
from .scrape_ysh import (
    YshCatalogMatch,
    build_indexes,
    load_catalog,
    normalize_title,
)
from .storage import episode_path_for, sha256_file

log = logging.getLogger(__name__)

# Match: optional leading whitespace, code prefix like "EE-11-02" or
# "B-4-02" (1–3 letters, dash, 1–3 digits volume, dash, 1–3 digits
# track), the " - " separator, and finally the title (greedy through
# the extension).
_YSH_FILENAME_RE = re.compile(
    r"^\s*(?P<code>[A-Z]{1,3}-\d{1,3}-\d{1,3})\s*-\s*(?P<title>.+?)\.(?:mp3|m4a)$",
    re.IGNORECASE,
)


@dataclass
class ParsedYshFile:
    path: Path
    code: Optional[str]           # uppercased "EE-11-02" or None
    title: str                    # best-effort title (filename, then ID3)


def parse_filename(name: str) -> tuple[Optional[str], Optional[str]]:
    """Extract (code, title) from a YSH-shaped filename. Returns
    (None, None) when the filename doesn't match the expected shape.

    Pure helper — visible for tests so we lock the regex contract
    without needing to set up a full drop-folder.
    """
    m = _YSH_FILENAME_RE.match(name)
    if not m:
        return None, None
    code = m.group("code").upper()
    title = m.group("title").strip().replace("_", " ")
    return code, title


def _read_id3_title(path: Path) -> Optional[str]:
    """Returns the ID3v2 TIT2 tag if present, else None. Mirrors the
    minimal-dependency shape used by import_dropbox.py — falls back
    silently when mutagen isn't installed."""
    try:
        from mutagen.id3 import ID3, ID3NoHeaderError  # type: ignore
    except ImportError:
        return None
    try:
        tags = ID3(path)
        if "TIT2" in tags:
            return str(tags["TIT2"]).strip() or None
    except ID3NoHeaderError:
        pass
    except Exception:
        pass
    return None


def parse_file(path: Path) -> ParsedYshFile:
    code, fn_title = parse_filename(path.name)
    title = fn_title or _read_id3_title(path) or path.stem
    return ParsedYshFile(path=path, code=code, title=title)


# =====================================================================
# Match resolution
# =====================================================================

def resolve_match(
    parsed: ParsedYshFile,
    code_index: dict[str, YshCatalogMatch],
    title_index: dict[str, list[YshCatalogMatch]],
) -> Optional[YshCatalogMatch]:
    """Try each catalog match strategy in priority order. Returns the
    first hit, or None for an unmatched file. Title-based matches
    that resolve to MULTIPLE albums (~65 titles per the probe) fall
    through silently — the importer treats them as unmatched and
    the operator's expected to rename the file with a code prefix.
    Visible for tests."""
    # 1. Filename code prefix.
    if parsed.code and parsed.code in code_index:
        return code_index[parsed.code]
    # 2. Filename title — only when unambiguous.
    key = normalize_title(parsed.title)
    if key:
        hits = title_index.get(key) or []
        if len(hits) == 1:
            return hits[0]
    # 3. ID3 title — same single-hit rule.
    id3_title = _read_id3_title(parsed.path)
    if id3_title:
        id3_key = normalize_title(id3_title)
        id3_hits = title_index.get(id3_key) or []
        if len(id3_hits) == 1:
            return id3_hits[0]
    return None


# =====================================================================
# File ingest
# =====================================================================

@dataclass
class YshImportSummary:
    scanned: int = 0
    imported: int = 0
    unmatched: int = 0
    errors: int = 0
    samples: list[str] = field(default_factory=list)


def _candidate_files(import_root: Path) -> list[Path]:
    """All mp3/m4a files under the import root, EXCLUDING the
    _unmatched/ subtree (re-run safety) and any hidden files."""
    out: list[Path] = []
    for path in sorted(import_root.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in (".mp3", ".m4a"):
            continue
        try:
            path.relative_to(IMPORT_UNMATCHED_DIR)
            continue   # already in _unmatched, skip
        except ValueError:
            pass
        if path.name.startswith("."):
            continue
        out.append(path)
    return out


def _move_unmatched(src: Path, reason: str) -> str:
    IMPORT_UNMATCHED_DIR.mkdir(parents=True, exist_ok=True)
    dst = IMPORT_UNMATCHED_DIR / src.name
    if dst.exists():
        dst = IMPORT_UNMATCHED_DIR / f"{dst.stem}-{src.stat().st_mtime_ns}{dst.suffix}"
    shutil.move(str(src), str(dst))
    return f"UNMATCHED  {src.name!r}  reason={reason}"


def _import_one(
    parsed: ParsedYshFile,
    code_index: dict[str, YshCatalogMatch],
    title_index: dict[str, list[YshCatalogMatch]],
) -> tuple[bool, str]:
    match = resolve_match(parsed, code_index, title_index)
    if not match:
        return False, _move_unmatched(parsed.path, "no catalog hit")

    # File destination — provider-aware path.
    external_id = str(match.sku_id)
    out_path = episode_path_for(
        provider_id="ysh",
        external_id=external_id,
        title=match.canonical_title,
        album=match.album_title,
    )
    out_path.parent.mkdir(parents=True, exist_ok=True)
    if out_path.exists():
        # Idempotent re-run — same sku already filed. Drop the
        # incoming duplicate without overwriting; operator can
        # decide whether to keep or trash the original.
        return False, _move_unmatched(parsed.path, "already-archived")

    shutil.move(str(parsed.path), str(out_path))
    size = out_path.stat().st_size
    sha = sha256_file(out_path)

    # Synthetic episode_id for YSH — the INTEGER PRIMARY KEY column
    # is NOT NULL, so we hash the sku_id deterministically (mirrors
    # the upload-route behavior of letting SQLite assign a rowid in
    # newer versions, but here we set it explicitly so re-imports
    # of the same sku land on the same row).
    episode_id = int(hashlib.sha256(f"ysh:{external_id}".encode()).hexdigest()[:12], 16)

    with db.connect() as c:
        c.execute(
            """INSERT OR IGNORE INTO episodes
               (episode_id, provider_id, external_id, title, album,
                file_path, file_size, sha256)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                episode_id, "ysh", external_id, match.canonical_title,
                match.album_title, str(out_path), size, sha,
            ),
        )
    return True, f"OK  {parsed.path.name!r}  →  {match.album_title}/{match.canonical_title}"


# =====================================================================
# Main entry
# =====================================================================

def run_import(
    import_root: Path = IMPORT_DIR,
    catalog_path: Path = YSH_CATALOG_PATH,
) -> YshImportSummary:
    """Walk `import_root` for mp3/m4a files and try to file each one
    against the YSH catalog. Returns a summary; logs per-file lines
    at INFO. Idempotent: re-running on an already-imported file moves
    the duplicate to `_unmatched/` rather than clobbering the archive.
    """
    summary = YshImportSummary()
    catalog = load_catalog(catalog_path)
    if not catalog:
        log.warning(
            "YSH catalog not loaded from %s — every file will be unmatched. "
            "Run scripts/refresh-ysh-catalog.sh first.",
            catalog_path,
        )
        catalog = {"albums": []}
    code_index, title_index, _sku_index = build_indexes(catalog)

    for src in _candidate_files(import_root):
        summary.scanned += 1
        try:
            parsed = parse_file(src)
            ok, line = _import_one(parsed, code_index, title_index)
        except Exception as e:
            summary.errors += 1
            summary.samples.append(f"ERROR  {src.name!r}  {e}")
            log.exception("YSH import error on %s", src)
            continue
        summary.samples.append(line)
        log.info(line)
        if ok:
            summary.imported += 1
        else:
            summary.unmatched += 1
    log.info(
        "YSH import done: scanned=%s imported=%s unmatched=%s errors=%s",
        summary.scanned, summary.imported, summary.unmatched, summary.errors,
    )
    return summary


def main() -> int:
    """Run via `python -m app.import_dropbox_ysh`. No arguments — the
    drop-folder + catalog paths come from config."""
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    summary = run_import()
    if summary.errors:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
