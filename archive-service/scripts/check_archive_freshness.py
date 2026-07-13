#!/usr/bin/env python3
"""
Detect gaps between what oneplace.com is publishing and what the
archive-service NAS has actually received.

The data flow is:

    oneplace.com  ──► Android app (DailyCheckWorker)  ──► archive-service

A gap on the NAS means either DailyCheckWorker missed the episode OR
ArchiveEpisodeWorker failed to upload it.

Re-broadcast aliasing: oneplace assigns a *new* episodeId (e.g.
1281611) every time it re-airs a classic AIO episode, but the Android
app uploads it under the canonical AIO catalog number from
aio_catalog.json (e.g. #298 "I Want My B-TV!"). Comparing oneplace
eids directly against NAS external_ids would flag every re-broadcast
as a false positive. So we translate via the bundled catalog:
title → AIO #, then look for either the oneplace eid OR the catalog #
on the NAS. Only when neither key is present is it a real gap.

CLI:
    check_archive_freshness.py \\
        --nas-url http://192.168.2.142:8088 \\
        --nas-token "$(cat ~/.aio-archive-token)" \\
        [--show-slug adventures-in-odyssey] \\
        [--show-id 777] \\
        [--probe-window 50] \\
        [--nas-limit 500] \\
        [--catalog-path archive-service/aio_catalog.json] \\
        [--no-catalog] \\
        [--json]

Exit code 0 when oneplace's recent window is fully on the NAS,
1 when at least one eid is missing, 2 on transport error (oneplace
unreachable, NAS auth failure, etc.).
"""
from __future__ import annotations

import argparse
import gzip
import json
import os
import re
import sys
import zlib
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, Iterable
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


USER_AGENT = "Mozilla/5.0 (Android) odyssey-app/freshness-check"
ONEPLACE_LISTEN_URL = "https://www.oneplace.com/ministries/{slug}/listen/"
ONEPLACE_API_URL = "https://www.oneplace.com/api/related-episodes"
BOOTSTRAP_RE = re.compile(r'episodeId[=:"\s]+(\d{6,})')
CATALOG_SHORTNAME_RE = re.compile(r"^#?(\d+[a-z]?):")
DEFAULT_CATALOG_PATH = (
    Path(__file__).resolve().parent.parent / "aio_catalog.json"
)


@dataclass(frozen=True)
class OneplaceEpisode:
    """A single AIO episode the upstream oneplace API surfaced.

    `catalog_number` is the AIO catalog episode # (string, e.g. "298"
    or "666a") looked up by title via aio_catalog.json. None when the
    title isn't in the catalog — that's expected for genuinely new
    broadcasts; the Android app uploads those under the oneplace eid.
    """
    episode_id: int
    title: str
    air_date: str | None  # "June 24, 2026" — oneplace's `subTitle` field
    catalog_number: str | None = None


HttpGet = Callable[[str, dict[str, str] | None], bytes]


def _default_http_get(url: str, headers: dict[str, str] | None = None) -> bytes:
    """GET with gzip/deflate handling. The Cloudflare in front of
    oneplace.com returns gzip even when Accept-Encoding is absent, and
    urllib.request doesn't auto-decompress — without this fix the
    bootstrap regex misses on a binary gzip blob."""
    req = Request(url, headers={"User-Agent": USER_AGENT, **(headers or {})})
    with urlopen(req, timeout=20) as resp:
        body = resp.read()
        encoding = (resp.headers.get("Content-Encoding") or "").lower()
    if encoding == "gzip":
        return gzip.decompress(body)
    if encoding == "deflate":
        return zlib.decompress(body)
    return body


def _norm_title(t: str) -> str:
    """Lowercase + collapse whitespace. Conservative — the catalog
    titles and oneplace titles are usually byte-identical, but
    occasionally differ in trailing spaces."""
    return " ".join(t.lower().split())


def load_catalog_index(path: Path) -> dict[str, str]:
    """Read aio_catalog.json and return normalized-title → AIO #.

    The catalog ships with the archive-service (`aio_catalog.json`)
    and is what the Android app uses to assign canonical episode
    numbers during ingest. Returns {} when the file is missing —
    callers fall back to eid-only gap detection in that case.
    """
    if not path.exists():
        return {}
    raw = json.loads(path.read_text(encoding="utf-8"))
    index: dict[str, str] = {}
    for album in raw.get("albums", []):
        for ep in album.get("episodes", []):
            name = (ep.get("name") or "").strip()
            short = ep.get("shortName") or ""
            m = CATALOG_SHORTNAME_RE.match(short)
            if name and m:
                # Later entries win on duplicate titles — bonus tracks
                # ("#666a") share parts of a name occasionally. The
                # ordering of duplicates is stable enough for our
                # use because the catalog file is hand-curated.
                index[_norm_title(name)] = m.group(1)
    return index


def fetch_oneplace_bootstrap_eid(
    show_slug: str,
    http_get: HttpGet = _default_http_get,
) -> int:
    """Scrape the listen page for the bootstrap episodeId — the same
    starting point AioOneplaceProvider uses. Raises ValueError when
    the regex doesn't match (oneplace HTML changed shape)."""
    html = http_get(
        ONEPLACE_LISTEN_URL.format(slug=show_slug), None
    ).decode("utf-8", errors="replace")
    m = BOOTSTRAP_RE.search(html)
    if not m:
        raise ValueError(
            f"bootstrap regex did not match on {show_slug} listen page — "
            "oneplace HTML probably changed; rerun scripts/probe-oneplace.sh"
        )
    return int(m.group(1))


def fetch_oneplace_recent(
    show_slug: str,
    show_id: int,
    probe_window: int,
    http_get: HttpGet = _default_http_get,
    catalog_index: dict[str, str] | None = None,
) -> list[OneplaceEpisode]:
    """Walk seed eid → seed+probe_window through /api/related-episodes,
    dedup by episodeId, return only items matching show_id.

    The probe-forward pattern mirrors OneplaceClient.newSince — the API
    silently changed semantics in 2026 and a single-page hit at the
    bootstrap can miss legitimate freshly-aired episodes.

    When `catalog_index` is provided each episode's `catalog_number` is
    populated by title lookup so the gap check can spot rows the
    Android app stored under an AIO catalog # rather than the
    oneplace eid (every re-broadcast does this).
    """
    seed = fetch_oneplace_bootstrap_eid(show_slug, http_get)
    seen: dict[int, OneplaceEpisode] = {}
    for offset in range(probe_window + 1):
        url = f"{ONEPLACE_API_URL}?{urlencode({'eid': seed + offset, 'ps': 20, 'watch': 'false'})}"
        try:
            body = http_get(url, {"Accept": "application/json"})
        except (HTTPError, URLError):
            continue
        try:
            items = json.loads(body)
        except json.JSONDecodeError:
            continue
        for it in items:
            if it.get("showId") != show_id:
                continue
            eid = it.get("episodeId")
            if not isinstance(eid, int) or eid in seen:
                continue
            title = it.get("title") or ""
            catalog_no = (catalog_index or {}).get(_norm_title(title))
            seen[eid] = OneplaceEpisode(
                episode_id=eid,
                title=title,
                air_date=it.get("subTitle") or None,
                catalog_number=catalog_no,
            )
    return sorted(seen.values(), key=lambda e: e.episode_id, reverse=True)


def fetch_nas_archived_eids(
    base_url: str,
    token: str,
    provider: str,
    limit: int,
    http_get: HttpGet = _default_http_get,
) -> set[str]:
    """Pull the most recent `limit` archive-service rows for `provider`
    and return their external_ids. Set semantics — caller uses set
    difference against oneplace eids to compute the gap.
    """
    url = f"{base_url.rstrip('/')}/providers/{provider}/episodes?limit={limit}"
    body = http_get(url, {"Authorization": f"Bearer {token}"})
    rows = json.loads(body)
    return {str(r["external_id"]) for r in rows}


def find_gap(
    oneplace: Iterable[OneplaceEpisode],
    archived_eids: set[str],
) -> list[OneplaceEpisode]:
    """Episodes oneplace knows about that the NAS doesn't, newest first.

    An episode is on the NAS if EITHER its oneplace eid OR its catalog
    number is present in `archived_eids` — the Android app uses the
    catalog # for re-broadcasts and the eid for genuinely new episodes,
    so both keys count as "archived".

    Asymmetric on purpose: NAS rows that aren't in the oneplace window
    are NOT gaps — they're just older episodes. The script's job is to
    catch *new* episodes the pipeline missed, not audit the back-catalog.
    """
    out: list[OneplaceEpisode] = []
    for ep in oneplace:
        keys = {str(ep.episode_id)}
        if ep.catalog_number:
            keys.add(ep.catalog_number)
        if not (keys & archived_eids):
            out.append(ep)
    return out


def format_report(
    oneplace: list[OneplaceEpisode],
    archived: set[str],
    gap: list[OneplaceEpisode],
) -> str:
    lines: list[str] = []
    lines.append(f"oneplace recent window: {len(oneplace)} AIO episodes")
    if oneplace:
        top = oneplace[0]
        lines.append(
            f"  newest oneplace: eid={top.episode_id}  air={top.air_date}  "
            f"catalog#={top.catalog_number or '-'}  title={top.title}"
        )
    lines.append(f"NAS archive sample:    {len(archived)} rows")
    if archived:
        newest_archived = max(int(e) for e in archived if e.isdigit())
        lines.append(f"  newest NAS eid:  {newest_archived}")
    lines.append("")
    if not gap:
        lines.append("OK — every oneplace episode in the window is on the NAS.")
        return "\n".join(lines)
    lines.append(f"GAP — {len(gap)} oneplace episode(s) missing from NAS:")
    for ep in gap:
        lines.append(
            f"  eid={ep.episode_id:<8} air={ep.air_date}  "
            f"catalog#={ep.catalog_number or '-':<5} title={ep.title}"
        )
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.strip().splitlines()[0])
    ap.add_argument("--nas-url", default=os.environ.get("ODYSSEY_NAS_URL"),
                    help="archive-service base URL (default: $ODYSSEY_NAS_URL)")
    ap.add_argument("--nas-token", default=os.environ.get("ODYSSEY_NAS_TOKEN"),
                    help="bearer token (default: $ODYSSEY_NAS_TOKEN)")
    ap.add_argument("--show-slug", default="adventures-in-odyssey")
    ap.add_argument("--show-id", type=int, default=777)
    ap.add_argument("--provider", default="aio")
    ap.add_argument("--probe-window", type=int, default=50,
                    help="how many eids forward of the bootstrap to probe")
    ap.add_argument("--nas-limit", type=int, default=500,
                    help="how many most-recent NAS rows to compare against")
    ap.add_argument("--catalog-path", default=str(DEFAULT_CATALOG_PATH),
                    help="path to aio_catalog.json for eid→AIO# translation")
    ap.add_argument("--no-catalog", action="store_true",
                    help="skip catalog translation; compare oneplace eid only")
    ap.add_argument("--json", action="store_true",
                    help="emit machine-readable JSON instead of a table")
    args = ap.parse_args(argv)

    if not args.nas_url or not args.nas_token:
        print("error: --nas-url + --nas-token (or env vars) are required",
              file=sys.stderr)
        return 2

    catalog_index: dict[str, str] = {}
    if not args.no_catalog:
        catalog_index = load_catalog_index(Path(args.catalog_path))

    try:
        oneplace = fetch_oneplace_recent(
            args.show_slug, args.show_id, args.probe_window,
            catalog_index=catalog_index,
        )
    except (HTTPError, URLError, ValueError) as e:
        print(f"error: oneplace fetch failed: {e}", file=sys.stderr)
        return 2

    try:
        archived = fetch_nas_archived_eids(
            args.nas_url, args.nas_token, args.provider, args.nas_limit
        )
    except (HTTPError, URLError) as e:
        print(f"error: NAS fetch failed: {e}", file=sys.stderr)
        return 2

    gap = find_gap(oneplace, archived)

    if args.json:
        print(json.dumps({
            "oneplace_count": len(oneplace),
            "nas_count": len(archived),
            "gap_count": len(gap),
            "catalog_loaded": len(catalog_index),
            "gap": [
                {
                    "episode_id": e.episode_id,
                    "title": e.title,
                    "air_date": e.air_date,
                    "catalog_number": e.catalog_number,
                }
                for e in gap
            ],
        }, indent=2))
    else:
        print(format_report(oneplace, archived, gap))

    return 1 if gap else 0


if __name__ == "__main__":
    sys.exit(main())
