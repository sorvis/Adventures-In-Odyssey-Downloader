"""
Server-side mirror of the Android-side YshCatalog. Fetches the full
yourstoryhour.org album catalog by paginating /crud/product/skus,
builds three lookup indexes the YSH file importer (step 13) consumes,
and persists the catalog to a JSON file on disk so the importer can
load it without re-hitting the network on every run.

JSON shape (mirrors aio_catalog.json enough that import-tooling can
share helpers):

  {
    "scrapedAtMs": <epoch_ms>,
    "albumCount": <int>,
    "albums": [
      {
        "id":  <int>,                       # yourstoryhour product_id
        "title": "Exciting Events - Volume 11",
        "slug":  "exciting-events-volume-11",
        "image": "https://your-story-hour.s3.amazonaws.com/.../EE-11.jpg",
        "code_prefix": "EE-11",              # null if series isn't in the map
        "lang_code": "en",
        "tracks": [
          {
            "sku_id":      1958,
            "title":       "Madeleine's Courage",
            "order_index": 2,
            "code":        "EE-11-02"        # null when code_prefix is null
          }
        ]
      }
    ]
  }

The code_prefix mapping is a curated series-name → code dictionary,
seeded from the four series I confirmed live (EE/GS/B/A). Add new
series as they're verified by checking a free-streaming sample's
download_url and extracting the prefix.
"""
from __future__ import annotations

import json
import logging
import re
import time
from pathlib import Path
from typing import Iterable

import httpx

log = logging.getLogger(__name__)

_API_URL = "https://www.yourstoryhour.org/crud/product/skus"
_TIMEOUT = httpx.Timeout(20.0)
_MAX_PAGES = 20

# Series-name prefix → (code, volume-padding) mapping derived from
# observed S3 download URLs on yourstoryhour.org's free-streaming
# endpoint:
#   EE-11-02 …   →  Exciting Events Vol 11   (2-digit vol)
#   GS-07-05 …   →  Great Stories Vol 7      (2-digit vol)
#   B-4-02 …     →  Bible Comes Alive 4      (1-digit vol)
#   A-08-19 …    →  Adventures in Life 8     (2-digit vol)
#
# Operators can extend this map by inspecting any free-streaming
# track URL: the literal prefix before the title is the (code-vol)
# pair.
_SERIES_CODES: dict[str, tuple[str, int]] = {
    "Exciting Events":     ("EE", 2),
    "Great Stories":       ("GS", 2),
    "Bible Comes Alive":   ("B", 1),
    "Adventures in Life":  ("A", 2),
}

# "Exciting Events - Volume 11" → series_name="Exciting Events", number=11
# "Bible Comes Alive - Album 4" → series_name="Bible Comes Alive",   number=4
# "Adventures in Life - Volume 8" → series_name="Adventures in Life", number=8
_ALBUM_TITLE_RE = re.compile(
    r"^(?P<series>.+?)\s*[-–]\s*(?:Volume|Album|Vol|Vol\.)\s*(?P<num>\d+)",
    re.IGNORECASE,
)


def derive_code_prefix(album_title: str) -> str | None:
    """Title → "<CODE>-<NN>" or None when the series isn't mapped.

    Pure helper. Volume numbers come through padded to 2 digits when
    the series uses that convention (EE/GS/A) and unpadded for B
    (Bible Comes Alive uses single-digit album numbers in its URLs).
    Visible for tests.
    """
    m = _ALBUM_TITLE_RE.match(album_title.strip())
    if not m:
        return None
    series = m.group("series").strip()
    entry = _SERIES_CODES.get(series)
    if entry is None:
        return None
    code, pad = entry
    num = int(m.group("num"))
    return f"{code}-{num:0{pad}d}"


def fetch_pages(
    api_url: str = _API_URL,
    client: httpx.Client | None = None,
    max_pages: int = _MAX_PAGES,
) -> list[dict]:
    """Walk /crud/product/skus?page=1..N until the response items list
    comes back empty. Returns the aggregated album rows deduplicated
    by id (the API doesn't paginate deterministically — same album can
    appear on multiple pages depending on sort).

    The `client` arg is for tests that want to inject a MockTransport;
    production passes None and we create a default client.
    """
    owns_client = client is None
    client = client or httpx.Client(
        timeout=_TIMEOUT,
        follow_redirects=True,
        headers={"User-Agent": "odyssey-archive/1.0", "Accept": "application/json"},
    )
    try:
        seen: dict[int, dict] = {}
        for page in range(1, max_pages + 1):
            r = client.get(api_url, params={"page": page})
            if r.status_code != 200:
                log.warning("scrape_ysh: HTTP %s for page=%s — stopping", r.status_code, page)
                break
            body = r.json()
            items = body.get("items") or []
            if not items:
                break
            for a in items:
                aid = a.get("id")
                if aid is not None:
                    seen[aid] = a
        return list(seen.values())
    finally:
        if owns_client:
            client.close()


def build_catalog(raw_albums: Iterable[dict]) -> dict:
    """Project the raw API rows into the persisted JSON shape (see
    module docstring). Filters to lang_code='en' and drops anything
    without digital_track SKUs."""
    albums: list[dict] = []
    for a in raw_albums:
        if (a.get("lang_code") or "en") != "en":
            continue
        title = (a.get("title") or "").strip()
        if not title:
            continue
        digital_tracks = [
            s for s in (a.get("skus") or [])
            if s.get("type") == "digital_track" and s.get("title")
        ]
        if not digital_tracks:
            continue
        code_prefix = derive_code_prefix(title)
        tracks_out = []
        for s in digital_tracks:
            order_index = s.get("order_index")
            track_code = (
                f"{code_prefix}-{(order_index or 0) + 1:02d}"
                if code_prefix is not None and order_index is not None
                else None
            )
            tracks_out.append({
                "sku_id":      s["id"],
                "title":       s["title"],
                "order_index": order_index,
                "code":        track_code,
            })
        albums.append({
            "id":          a.get("id"),
            "title":       title,
            "slug":        a.get("slug"),
            "image":       a.get("primary_image"),
            "code_prefix": code_prefix,
            "lang_code":   a.get("lang_code") or "en",
            "tracks":      tracks_out,
        })
    return {
        "scrapedAtMs": int(time.time() * 1000),
        "albumCount":  len(albums),
        "albums":      albums,
    }


def refresh_catalog(out_path: Path, **fetch_kwargs) -> dict:
    """Walk the live catalog, build the index, write to disk, return
    the persisted dict. Used by the CLI script + future "refresh
    catalog now" admin endpoint."""
    raw = fetch_pages(**fetch_kwargs)
    catalog = build_catalog(raw)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(catalog, indent=2))
    log.info(
        "scrape_ysh: wrote %s albums (%s tracks) to %s",
        catalog["albumCount"],
        sum(len(a["tracks"]) for a in catalog["albums"]),
        out_path,
    )
    return catalog


# =====================================================================
# Lookup indexes — used by the importer in step 13
# =====================================================================

from dataclasses import dataclass


@dataclass(frozen=True)
class YshCatalogMatch:
    sku_id: int
    canonical_title: str
    album_title: str
    album_slug: str
    track_order: int | None
    code_prefix: str | None
    code: str | None


def load_catalog(path: Path) -> dict | None:
    """Load the persisted catalog from disk, or None if the file
    doesn't exist (fresh install before refresh has run)."""
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text())
    except Exception as e:
        log.warning("scrape_ysh: failed to load %s: %s", path, e)
        return None


def build_indexes(catalog: dict) -> tuple[
    dict[str, YshCatalogMatch],   # code_index: "EE-11-02" → match
    dict[str, list[YshCatalogMatch]],  # title_index: normalized → matches
    dict[int, YshCatalogMatch],    # sku_index: sku_id → match
]:
    """Three lookup tables keyed by:
      - exact track code (`<series>-<vol>-<track>`) — unique
      - normalized title — may have multiple hits when a title appears
        in more than one album (~65 cases out of 1055 tracks per the
        probe; the importer disambiguates via code prefix or falls
        back to /import/_unmatched/ for operator review)
      - sku_id — always unique
    """
    code_index: dict[str, YshCatalogMatch] = {}
    title_index: dict[str, list[YshCatalogMatch]] = {}
    sku_index: dict[int, YshCatalogMatch] = {}
    for album in catalog.get("albums", []):
        a_title = album.get("title") or ""
        a_slug = album.get("slug") or ""
        a_prefix = album.get("code_prefix")
        for t in album.get("tracks", []):
            m = YshCatalogMatch(
                sku_id=t["sku_id"],
                canonical_title=t["title"],
                album_title=a_title,
                album_slug=a_slug,
                track_order=t.get("order_index"),
                code_prefix=a_prefix,
                code=t.get("code"),
            )
            if m.code:
                code_index[m.code] = m
            sku_index[m.sku_id] = m
            key = normalize_title(m.canonical_title)
            title_index.setdefault(key, []).append(m)
    return code_index, title_index, sku_index


def normalize_title(s: str) -> str:
    """Same shape as the Android-side normalize: lowercase, strip
    non-alphanumeric, collapse whitespace."""
    out: list[str] = []
    last_was_space = False
    for raw in s:
        ch = raw.lower()
        if ch.isalnum():
            out.append(ch)
            last_was_space = False
        else:
            if not last_was_space and out:
                out.append(" ")
            last_was_space = True
    return "".join(out).strip()
