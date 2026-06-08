"""Backfill the `album` column on YSH episodes that landed in the
Unsorted bucket (`album IS NULL OR album = ''`).

Why this exists: pre-2026-06 ingest paths for YSH didn't always
populate the album field — the Android upload route only auto-enriches
AIO, and a chunk of early YSH rows came in via paths that left album
NULL. The YSH catalog (yourstoryhour.org/crud/product/skus) maps each
sku_id → album_title unambiguously, so we can fix the existing rows
with a deterministic lookup.

Idempotent. Re-runnable. Refreshes the catalog from upstream when
`--refresh` is passed or the on-disk catalog is missing.

Invoke:
    docker compose exec -T archive python -m app.backfill_ysh_albums
    docker compose exec -T archive python -m app.backfill_ysh_albums --dry-run
    docker compose exec -T archive python -m app.backfill_ysh_albums --refresh

Pattern mirrors `app.import_dropbox_ysh` — script wrapper at
`scripts/backfill-ysh-albums.sh` for the operator.
"""
from __future__ import annotations

import argparse
import logging
from dataclasses import dataclass
from pathlib import Path

from .config import YSH_CATALOG_PATH
from .db import connect
from .scrape_ysh import (
    YshCatalogMatch,
    build_indexes,
    load_catalog,
    refresh_catalog,
)
from .storage import episode_path_for

log = logging.getLogger(__name__)

_EXTID_PREFIX = "ysh-sku-"


@dataclass(frozen=True)
class BackfillSummary:
    """What a run did. Used by tests + the CLI's exit log."""
    rows_scanned: int = 0
    rows_matched: int = 0
    rows_unmatched: int = 0
    files_moved: int = 0
    files_missing: int = 0


def _parse_sku(external_id: str | None) -> int | None:
    if not external_id or not external_id.startswith(_EXTID_PREFIX):
        return None
    try:
        return int(external_id[len(_EXTID_PREFIX):])
    except ValueError:
        return None


def run_backfill(
    *,
    refresh: bool = False,
    dry_run: bool = False,
    catalog_path: Path = YSH_CATALOG_PATH,
) -> BackfillSummary:
    """Find YSH rows with NULL/empty album, look each one up by sku_id,
    update title/album/file_path, and move the audio file to its new
    canonical path (`audio/ysh/<album-slug>/<sku_id>-<title>.mp3`).

    title is also rewritten to the catalog's canonical_title — the
    YSH ingest stored the publisher-supplied title which is usually
    identical but occasionally differs in punctuation/spacing.
    """
    if refresh or not catalog_path.exists():
        log.info("refreshing YSH catalog → %s", catalog_path)
        refresh_catalog(catalog_path)
    catalog = load_catalog(catalog_path)
    if catalog is None:
        log.error("could not load catalog from %s", catalog_path)
        return BackfillSummary()
    _, _, sku_index = build_indexes(catalog)
    log.info("loaded %d sku_id → album entries", len(sku_index))

    summary = BackfillSummary()
    with connect() as c:
        rows = c.execute(
            "SELECT episode_id, external_id, title, album, file_path "
            "FROM episodes WHERE provider_id = 'ysh' "
            "AND (album IS NULL OR album = '')"
        ).fetchall()
        rows_scanned = len(rows)
        rows_matched = rows_unmatched = files_moved = files_missing = 0
        log.info("found %d YSH row(s) with no album", rows_scanned)

        for row in rows:
            ext = row["external_id"]
            sku = _parse_sku(ext)
            if sku is None:
                log.warning(
                    "skip ep=%s: external_id %r doesn't look like ysh-sku-<n>",
                    row["episode_id"], ext,
                )
                rows_unmatched += 1
                continue
            match: YshCatalogMatch | None = sku_index.get(sku)
            if match is None:
                log.warning(
                    "skip ep=%s sku=%d: not in catalog (out of print? "
                    "or catalog stale?)",
                    row["episode_id"], sku,
                )
                rows_unmatched += 1
                continue

            new_album = match.album_title
            new_title = match.canonical_title
            old_path = Path(row["file_path"])
            new_path = episode_path_for("ysh", ext, new_title, new_album)
            log.info(
                "ep=%s sku=%d  album=%r  title=%r%s",
                row["episode_id"], sku, new_album, new_title,
                "  [dry-run]" if dry_run else "",
            )
            rows_matched += 1
            if dry_run:
                continue

            if new_path != old_path:
                new_path.parent.mkdir(parents=True, exist_ok=True)
                if old_path.exists():
                    old_path.replace(new_path)
                    files_moved += 1
                else:
                    log.warning(
                        "ep=%s file missing at %s — DB updated but no "
                        "file moved", row["episode_id"], old_path,
                    )
                    files_missing += 1
            c.execute(
                "UPDATE episodes SET album = ?, title = ?, file_path = ?, "
                "title_validated_at = COALESCE(title_validated_at, datetime('now')) "
                "WHERE episode_id = ?",
                (
                    new_album,
                    new_title,
                    str(new_path) if new_path != old_path else row["file_path"],
                    row["episode_id"],
                ),
            )

        summary = BackfillSummary(
            rows_scanned=rows_scanned,
            rows_matched=rows_matched,
            rows_unmatched=rows_unmatched,
            files_moved=files_moved,
            files_missing=files_missing,
        )
    log.info(
        "backfill done: scanned=%d matched=%d unmatched=%d "
        "files_moved=%d files_missing=%d",
        summary.rows_scanned, summary.rows_matched, summary.rows_unmatched,
        summary.files_moved, summary.files_missing,
    )
    return summary


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--refresh", action="store_true",
                   help="re-scrape upstream before running")
    p.add_argument("--dry-run", action="store_true",
                   help="show what would change without writing")
    p.add_argument("--catalog-path", type=Path, default=YSH_CATALOG_PATH)
    args = p.parse_args()
    summary = run_backfill(
        refresh=args.refresh,
        dry_run=args.dry_run,
        catalog_path=args.catalog_path,
    )
    return 0 if summary.rows_unmatched == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
