"""Backfill the `description` column on AIO episodes whose value is
currently NULL.

The original ingest pipeline only populated description for episodes
scraped via the modern oneplace `related-episodes` endpoint. Older
broadcast-numbered rows (imported via the C# downloader) and any row
imported before the description field landed sit with NULL — so the
NowPlayingScreen's description block (v0.1.82+) renders empty for
them. This script walks oneplace backwards from a configurable
anchor episodeId, filters to AIO (`showId=777`), and PATCHes each
null-description row whose episodeId shows up in the API stream.

What it CAN'T fix: pre-oneplace back-catalog episodes whose
episode_id is a tiny broadcast number (e.g. 82 "Heatwave"). Those
were never on oneplace's CMS — descriptions for them would have to
come from a different source (AIO Club, Whits End site, etc.). The
script counts them in the "unreachable" bucket.

Pattern mirrors `app.backfill_ysh_albums` — invoke via:
    docker compose exec -T archive python -m app.backfill_aio_descriptions
    docker compose exec -T archive python -m app.backfill_aio_descriptions --dry-run
    docker compose exec -T archive python -m app.backfill_aio_descriptions --max-pages 50

Polite to oneplace: 50 episodes/page (the API's default), 500ms
sleep between page requests, configurable cap.
"""
from __future__ import annotations

import argparse
import json
import logging
import time
from dataclasses import dataclass
from typing import Callable
from urllib.request import Request, urlopen

from .db import connect

log = logging.getLogger(__name__)

# oneplace's numeric show identifier for Adventures in Odyssey.
_AIO_SHOW_ID = 777
_API_URL = "https://www.oneplace.com/api/related-episodes"
# CMS-id threshold — episodes below this came from broadcast-number
# imports (pre-oneplace) and aren't fetchable via this API.
_MIN_CMS_ID = 1_000_000


@dataclass(frozen=True)
class BackfillResult:
    rows_scanned: int = 0
    rows_unreachable: int = 0  # below CMS-id threshold
    rows_patched: int = 0
    pages_fetched: int = 0


def _default_fetcher(eid: int, page_size: int = 50) -> list[dict]:
    """One related-episodes call. Returns a list of episode dicts in
    descending eid order (newest first), excluding the seed eid.
    Network/HTTP errors raise."""
    url = f"{_API_URL}?eid={eid}&ps={page_size}&watch=false&showId={_AIO_SHOW_ID}"
    req = Request(url, headers={
        "User-Agent": "odyssey-archive/backfill 1.0",
        "Accept": "application/json",
    })
    with urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def run_backfill(
    *,
    dry_run: bool = False,
    max_pages: int = 200,
    anchor: int | None = None,
    page_size: int = 50,
    sleep_secs: float = 0.5,
    fetcher: Callable[[int, int], list[dict]] = _default_fetcher,
) -> BackfillResult:
    """Walk oneplace backwards collecting descriptions for AIO rows
    with NULL description. `anchor` defaults to (max-null-eid + 1) so
    the first page includes the most recent missing row.

    `fetcher` is injectable for tests — production passes the real
    `_default_fetcher`. Signature: (cursor_eid, page_size) -> list[dict].
    """
    # 1. Read every AIO row with NULL description from the DB.
    with connect() as c:
        rows = c.execute(
            "SELECT episode_id FROM episodes "
            "WHERE provider_id = 'aio' "
            "AND (description IS NULL OR description = '') "
            "ORDER BY episode_id DESC"
        ).fetchall()
    missing: set[int] = {r["episode_id"] for r in rows}
    rows_scanned = len(missing)
    unreachable = {eid for eid in missing if eid < _MIN_CMS_ID}
    reachable = missing - unreachable
    log.info(
        "found %d AIO rows missing description (%d in CMS range, "
        "%d below CMS threshold — pre-oneplace back catalog)",
        rows_scanned, len(reachable), len(unreachable),
    )
    if not reachable:
        return BackfillResult(
            rows_scanned=rows_scanned,
            rows_unreachable=len(unreachable),
        )

    # 2. Walk backwards from (max + 1) so the first response includes
    # the highest missing eid. Each page also walks the cursor for us.
    cursor = anchor if anchor is not None else max(reachable) + 1
    patched = 0
    pages_fetched = 0
    for _ in range(max_pages):
        if not reachable:
            break
        log.debug("fetching page eid<=%d (remaining %d)", cursor, len(reachable))
        try:
            page = fetcher(cursor, page_size)
        except Exception as exc:
            log.warning("fetch failed at cursor=%d: %s — stopping early",
                        cursor, exc)
            break
        pages_fetched += 1
        if not page:
            log.info("oneplace returned empty page at cursor=%d — done", cursor)
            break

        for ep in page:
            try:
                eid = int(ep.get("episodeId", 0))
            except (TypeError, ValueError):
                continue
            if eid in reachable:
                desc = (ep.get("description") or "").strip()
                if not desc:
                    continue
                if not dry_run:
                    with connect() as c:
                        c.execute(
                            "UPDATE episodes SET description = ? "
                            "WHERE episode_id = ? AND provider_id = 'aio'",
                            (desc, eid),
                        )
                patched += 1
                reachable.discard(eid)
                log.info("ep=%d patched (desc %d chars)%s",
                         eid, len(desc), "  [dry-run]" if dry_run else "")

        # Walk cursor to the oldest eid we just saw, minus 1.
        oldest = min(int(e.get("episodeId", 0)) for e in page
                     if e.get("episodeId"))
        if oldest <= 0 or oldest >= cursor:
            log.info("cursor stuck at %d (page oldest=%d) — stopping",
                     cursor, oldest)
            break
        cursor = oldest
        time.sleep(sleep_secs)

    if reachable:
        log.warning(
            "%d CMS-range row(s) didn't appear in the API stream "
            "(deleted from oneplace?) — examples: %s",
            len(reachable),
            sorted(reachable)[:5],
        )

    return BackfillResult(
        rows_scanned=rows_scanned,
        rows_unreachable=len(unreachable),
        rows_patched=patched,
        pages_fetched=pages_fetched,
    )


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--max-pages", type=int, default=200,
                   help="safety cap on related-episodes pagination "
                        "(default 200 = 10000 episodes scanned)")
    p.add_argument("--anchor", type=int, default=None,
                   help="seed cursor episodeId (defaults to max-null + 1)")
    p.add_argument("--page-size", type=int, default=50)
    p.add_argument("--sleep-secs", type=float, default=0.5)
    args = p.parse_args()
    result = run_backfill(
        dry_run=args.dry_run,
        max_pages=args.max_pages,
        anchor=args.anchor,
        page_size=args.page_size,
        sleep_secs=args.sleep_secs,
    )
    log.info(
        "backfill done: scanned=%d unreachable=%d patched=%d pages=%d",
        result.rows_scanned, result.rows_unreachable,
        result.rows_patched, result.pages_fetched,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
