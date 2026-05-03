"""
Best-effort album enrichment from the official Adventures in Odyssey site.

Strategy: hit the public AIO Club search page with the episode title and grep
the result page for an album/series name. Cached in SQLite to avoid repeats.

The exact endpoint is verified at runtime — if AIO changes its markup we fall
back to None and the episode is filed under 'Unsorted'.
"""
import logging
import re
from urllib.parse import quote_plus
import httpx
from . import db

log = logging.getLogger(__name__)

_AIO_SEARCH = "https://www.adventuresinodyssey.com/?s={q}"
_ALBUM_RE = re.compile(
    r'(album|series)["\s:>=]+([A-Z][^<"\']{2,80})', re.IGNORECASE
)
_TIMEOUT = httpx.Timeout(10.0)


def enrich_album(title: str) -> str | None:
    key = title.strip().lower()
    with db.connect() as c:
        row = c.execute(
            "SELECT album FROM album_cache WHERE title_key = ?", (key,)
        ).fetchone()
        if row:
            return row["album"]

    album = _scrape(title)
    with db.connect() as c:
        c.execute(
            "INSERT OR REPLACE INTO album_cache(title_key, album) VALUES (?, ?)",
            (key, album),
        )
    return album


def _scrape(title: str) -> str | None:
    url = _AIO_SEARCH.format(q=quote_plus(title))
    try:
        with httpx.Client(timeout=_TIMEOUT, follow_redirects=True) as c:
            r = c.get(url, headers={"User-Agent": "odyssey-archive/1.0"})
        if r.status_code != 200:
            return None
        m = _ALBUM_RE.search(r.text)
        return m.group(2).strip() if m else None
    except Exception as e:
        log.warning("AIO album lookup failed for %r: %s", title, e)
        return None
