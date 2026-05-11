"""
Tests for app.scrape_ysh — the server-side YSH catalog scraper.

Drives the fetch loop through an injected httpx.MockTransport so
nothing hits yourstoryhour.org over the wire. Verifies the pure
helpers (`derive_code_prefix`, `normalize_title`, `build_catalog`,
`build_indexes`) directly and the end-to-end refresh flow against
a synthetic two-page response.
"""
from __future__ import annotations

import json
from pathlib import Path

import httpx
import pytest

from app.scrape_ysh import (
    YshCatalogMatch,
    build_catalog,
    build_indexes,
    derive_code_prefix,
    fetch_pages,
    load_catalog,
    normalize_title,
    refresh_catalog,
)


# ===== pure helpers ===================================================


def test_derive_code_prefix_for_known_series():
    assert derive_code_prefix("Exciting Events - Volume 11") == "EE-11"
    assert derive_code_prefix("Great Stories - Volume 7") == "GS-07"
    assert derive_code_prefix("Bible Comes Alive - Album 4") == "B-4"
    assert derive_code_prefix("Adventures in Life - Volume 1") == "A-01"


def test_derive_code_prefix_returns_None_for_unknown_series():
    # Series isn't in the curated map — caller falls back to title
    # matching, no code prefix.
    assert derive_code_prefix("Some Other Show - Volume 1") is None


def test_derive_code_prefix_returns_None_for_unparseable_title():
    assert derive_code_prefix("Passion of Jesus (Compilation)") is None
    assert derive_code_prefix("") is None


def test_normalize_title_lowercases_and_strips_punctuation():
    assert normalize_title("The $14 Horse") == "the 14 horse"
    assert normalize_title("Child of Privilege (Lottie Moon Part 1)") == \
        "child of privilege lottie moon part 1"
    # Whitespace collapses; smart quotes treated as punctuation.
    assert normalize_title("  smart” quotes-ok  ") == "smart quotes ok"
    assert normalize_title("") == ""
    assert normalize_title("   ") == ""


# ===== build_catalog ==================================================


def _api_album(**overrides):
    base = {
        "id": 119,
        "title": "Exciting Events - Volume 11",
        "slug": "exciting-events-volume-11",
        "primary_image": "https://s3/EE-11.jpg",
        "lang_code": "en",
        "skus": [
            {"id": 1958, "title": "Madeleine's Courage",
             "type": "digital_track", "order_index": 1},
            {"id": 1959, "title": "Other Story",
             "type": "digital_track", "order_index": 2},
            # Non-track SKUs should be filtered out.
            {"id": 1960, "title": "MP3", "type": "digital_album"},
            {"id": 1961, "title": "Audio CD", "type": "physical"},
        ],
    }
    base.update(overrides)
    return base


def test_build_catalog_keeps_only_digital_track_skus_and_english_albums():
    raw = [
        _api_album(),
        _api_album(id=99, title="Pasión de Cristo", slug="pasion",
                   lang_code="es",
                   skus=[{"id": 200, "title": "Una",
                          "type": "digital_track", "order_index": 0}]),
    ]
    catalog = build_catalog(raw)
    assert catalog["albumCount"] == 1   # Spanish album dropped
    album = catalog["albums"][0]
    assert album["title"] == "Exciting Events - Volume 11"
    assert album["code_prefix"] == "EE-11"
    assert [t["sku_id"] for t in album["tracks"]] == [1958, 1959]


def test_build_catalog_assigns_track_codes_when_album_has_a_prefix():
    raw = [_api_album()]
    catalog = build_catalog(raw)
    tracks = catalog["albums"][0]["tracks"]
    # order_index 1 → "<prefix>-02" (1-based track number, zero-padded)
    assert tracks[0]["code"] == "EE-11-02"
    # order_index 2 → "<prefix>-03"
    assert tracks[1]["code"] == "EE-11-03"


def test_build_catalog_leaves_track_code_None_for_unknown_series():
    raw = [_api_album(title="Some Other Show - Volume 1",
                      skus=[{"id": 500, "title": "Story", "type": "digital_track",
                             "order_index": 0}])]
    catalog = build_catalog(raw)
    track = catalog["albums"][0]["tracks"][0]
    assert track["code"] is None
    assert catalog["albums"][0]["code_prefix"] is None


def test_build_catalog_drops_albums_with_no_digital_track_skus():
    raw = [_api_album(skus=[
        {"id": 99, "title": "MP3", "type": "digital_album"},
    ])]
    assert build_catalog(raw)["albumCount"] == 0


def test_build_catalog_handles_missing_lang_code_as_english():
    raw = [_api_album(lang_code=None)]
    assert build_catalog(raw)["albumCount"] == 1


# ===== fetch_pages (httpx mock transport) =============================


def test_fetch_pages_paginates_until_empty_response():
    pages = {
        1: {"items": [{"id": 1, "title": "Album A"}]},
        2: {"items": [{"id": 2, "title": "Album B"}]},
        3: {"items": []},
    }
    def handler(req: httpx.Request) -> httpx.Response:
        page = int(req.url.params.get("page", "1"))
        return httpx.Response(200, json=pages.get(page, {"items": []}))
    transport = httpx.MockTransport(handler)
    with httpx.Client(transport=transport) as client:
        out = fetch_pages(api_url="http://x/api", client=client)
    assert sorted(a["id"] for a in out) == [1, 2]


def test_fetch_pages_dedupes_albums_by_id_across_pages():
    pages = {
        1: {"items": [{"id": 1, "title": "Dup"}]},
        2: {"items": [{"id": 1, "title": "Dup-updated"}]},  # same id, different content
        3: {"items": []},
    }
    def handler(req):
        return httpx.Response(200, json=pages[int(req.url.params.get("page", "1"))])
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        out = fetch_pages(api_url="http://x/api", client=client)
    assert len(out) == 1
    # Last seen wins on dedup.
    assert out[0]["title"] == "Dup-updated"


def test_fetch_pages_stops_on_http_error():
    def handler(req):
        return httpx.Response(500)
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        out = fetch_pages(api_url="http://x/api", client=client)
    assert out == []


def test_fetch_pages_respects_max_pages_safety_cap():
    # Server keeps returning items — fetch_pages must not loop forever.
    def handler(req):
        page = int(req.url.params.get("page", "1"))
        return httpx.Response(200, json={"items": [{"id": page, "title": f"A{page}"}]})
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        out = fetch_pages(api_url="http://x/api", client=client, max_pages=3)
    assert len(out) == 3


# ===== refresh_catalog (disk roundtrip) ==============================


def test_refresh_catalog_writes_json_to_disk(tmp_path: Path):
    pages = {
        1: {"items": [_api_album()]},
        2: {"items": []},
    }
    def handler(req):
        return httpx.Response(200, json=pages[int(req.url.params.get("page", "1"))])
    out_path = tmp_path / "subdir" / "ysh_catalog.json"  # mkdirs the parent
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        result = refresh_catalog(out_path, api_url="http://x/api", client=client)
    assert out_path.exists()
    on_disk = json.loads(out_path.read_text())
    assert on_disk == result
    assert on_disk["albumCount"] == 1
    assert on_disk["albums"][0]["code_prefix"] == "EE-11"


# ===== load_catalog ==================================================


def test_load_catalog_returns_None_when_file_missing(tmp_path):
    assert load_catalog(tmp_path / "missing.json") is None


def test_load_catalog_returns_None_on_corrupt_json(tmp_path):
    p = tmp_path / "broken.json"
    p.write_text("not valid json {")
    assert load_catalog(p) is None


def test_load_catalog_round_trips_a_well_formed_file(tmp_path):
    p = tmp_path / "ok.json"
    payload = {"scrapedAtMs": 1, "albumCount": 0, "albums": []}
    p.write_text(json.dumps(payload))
    assert load_catalog(p) == payload


# ===== build_indexes =================================================


def _two_album_catalog():
    return build_catalog([
        _api_album(),  # EE-11 with two tracks
        _api_album(
            id=44, title="Bible Comes Alive - Album 4", slug="bca-4",
            primary_image="https://s3/B-4.jpg", lang_code="en",
            skus=[
                {"id": 800, "title": "The Land of Uz", "type": "digital_track",
                 "order_index": 1},
                # Re-used title that ALSO exists in another album — the
                # title_index must keep both hits.
                {"id": 801, "title": "Madeleine's Courage", "type": "digital_track",
                 "order_index": 2},
            ],
        ),
    ])


def test_build_indexes_code_index_unique_per_track():
    code_index, _, _ = build_indexes(_two_album_catalog())
    assert "EE-11-02" in code_index
    assert "B-4-02" in code_index
    assert code_index["EE-11-02"].canonical_title == "Madeleine's Courage"
    assert code_index["EE-11-02"].album_title == "Exciting Events - Volume 11"


def test_build_indexes_title_index_collects_multiple_album_hits():
    _, title_index, _ = build_indexes(_two_album_catalog())
    # "Madeleine's Courage" appears in BOTH albums.
    matches = title_index[normalize_title("Madeleine's Courage")]
    assert len(matches) == 2
    assert {m.album_title for m in matches} == {
        "Exciting Events - Volume 11",
        "Bible Comes Alive - Album 4",
    }


def test_build_indexes_sku_index_keyed_by_int_sku_id():
    _, _, sku_index = build_indexes(_two_album_catalog())
    assert sku_index[1958].canonical_title == "Madeleine's Courage"
    assert sku_index[800].canonical_title == "The Land of Uz"


def test_build_indexes_skips_tracks_with_no_code_when_album_has_no_prefix():
    catalog = build_catalog([_api_album(
        title="Unknown Series - Volume 1",
        skus=[{"id": 555, "title": "Story", "type": "digital_track",
               "order_index": 0}],
    )])
    code_index, title_index, sku_index = build_indexes(catalog)
    # No code, so code_index is empty.
    assert code_index == {}
    # But the title and sku indexes still pick up the track.
    assert sku_index[555].canonical_title == "Story"
    assert title_index[normalize_title("Story")][0].sku_id == 555
