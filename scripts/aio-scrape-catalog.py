#!/usr/bin/env python3
"""
Pull the full public AIO album catalog from the Salesforce-backed
content-grouping API and write a slim JSON asset for the Android app.

Endpoint discovered by intercepting `app.adventuresinodyssey.com/albums`
in headless Chromium:

    POST https://fotf.my.site.com/aio/services/apexrest/v1/contentgrouping/search
    Headers: X-Experience-Name: Adventures In Odyssey
             Content-Type: application/json
             Referer: https://app.adventuresinodyssey.com/
    Body:    {"type":"Album","community":"Adventures in Odyssey",
              "pageNumber":N,"pageSize":25}

No OAuth token required for this read-only catalog endpoint —
content under each album returns thumbnails+names but `link_to_object`
(the audio URL) is gated for paying members.

Run:
    scripts/aio-scrape-catalog.py
        → writes android/app/src/main/assets/aio_catalog.json
"""
from __future__ import annotations
import json
import sys
import time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import URLError, HTTPError

ENDPOINT = "https://fotf.my.site.com/aio/services/apexrest/v1/contentgrouping/search"
OUT = Path(__file__).resolve().parent.parent / "android" / "app" / "src" / "main" / "assets" / "aio_catalog.json"
PAGE_SIZE = 25


def fetch_page(page_number: int) -> dict:
    body = json.dumps({
        "type": "Album",
        "community": "Adventures in Odyssey",
        "pageNumber": page_number,
        "pageSize": PAGE_SIZE,
    }).encode("utf-8")
    req = Request(
        ENDPOINT,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "X-Experience-Name": "Adventures In Odyssey",
            "Referer": "https://app.adventuresinodyssey.com/",
            "User-Agent": "odyssey-catalog-scraper/0.1",
        },
    )
    with urlopen(req, timeout=20) as r:
        return json.loads(r.read())


def slim_album(album: dict) -> dict:
    """Keep only what we need to enrich oneplace.com episodes."""
    return {
        "albumNumber": album.get("album_number"),
        "name": album.get("name"),
        "imageUrl": album.get("imageURL"),
        "description": album.get("full_description") or album.get("description"),
        "totalRuntimeMs": album.get("total_runtime"),
        "episodes": [
            {
                "name": ep.get("name") or "",
                "shortName": ep.get("short_name") or "",
                "thumbnailSmall": ep.get("thumbnail_small"),
                "thumbnailMedium": ep.get("thumbnail_medium"),
                "mediaLengthMs": ep.get("media_length"),
                "subtype": ep.get("subtype"),
                "description": ep.get("description"),
            }
            for ep in (album.get("contentList") or [])
            if (ep.get("name") or ep.get("short_name"))  # skip blanks
        ],
    }


def main() -> int:
    albums: list[dict] = []
    page = 1
    while True:
        try:
            resp = fetch_page(page)
        except (URLError, HTTPError) as e:
            print(f"  ! page {page} failed: {e}", file=sys.stderr)
            return 1

        cgs = resp.get("contentGroupings") or []
        meta = resp.get("metadata") or {}
        total_pages = meta.get("totalPageCount", 0)
        total_records = meta.get("totalRecordCount", 0)
        print(f"page {page}/{total_pages} — got {len(cgs)} albums (total {total_records})")

        for cg in cgs:
            albums.append(slim_album(cg))

        if page >= total_pages or not cgs:
            break
        page += 1
        # Be polite — small delay between pages so we don't rate-limit ourselves.
        time.sleep(0.4)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({
        "scrapedAtMs": int(time.time() * 1000),
        "albumCount": len(albums),
        "albums": albums,
    }, ensure_ascii=False))
    size_kb = OUT.stat().st_size / 1024
    total_eps = sum(len(a["episodes"]) for a in albums)
    print(f"\n✔ wrote {OUT}")
    print(f"  {len(albums)} albums, {total_eps} episodes, {size_kb:.0f} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
