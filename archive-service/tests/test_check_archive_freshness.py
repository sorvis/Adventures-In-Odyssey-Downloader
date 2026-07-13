"""Tests for scripts/check_archive_freshness.py.

The script's reason to exist is detecting the case the live system was
in on 2026-06-26: NAS hasn't received a new AIO episode since
2026-05-08 (eid 1278383) while oneplace has 10+ newer ones at
eid 1281607+. These tests pin that exact gap detection — feed the
fetchers payloads shaped like the real oneplace + NAS responses and
prove the script flags the gap.
"""
from __future__ import annotations

import importlib.util
import json
import sys
from pathlib import Path

import pytest


def _load_script():
    here = Path(__file__).resolve().parent.parent
    spec = importlib.util.spec_from_file_location(
        "check_archive_freshness",
        here / "scripts" / "check_archive_freshness.py",
    )
    mod = importlib.util.module_from_spec(spec)
    sys.modules["check_archive_freshness"] = mod
    spec.loader.exec_module(mod)
    return mod


CAF = _load_script()


# ---------------------------------------------------------------------------
# find_gap — the pure-function core
# ---------------------------------------------------------------------------


def _ep(eid: int, title: str = "ep", air: str = "May 1, 2026", cat: str | None = None):
    return CAF.OneplaceEpisode(
        episode_id=eid, title=title, air_date=air, catalog_number=cat,
    )


def test_find_gap_flags_missing_eids():
    oneplace = [_ep(1281619), _ep(1281618), _ep(1281617), _ep(1281616)]
    archived = {"1281617", "1281616"}
    gap = CAF.find_gap(oneplace, archived)
    assert [e.episode_id for e in gap] == [1281619, 1281618]


def test_find_gap_empty_when_all_archived():
    oneplace = [_ep(1281619), _ep(1281618)]
    archived = {"1281619", "1281618", "1278383"}
    assert CAF.find_gap(oneplace, archived) == []


def test_find_gap_ignores_nas_extras():
    """NAS rows older than oneplace's window are NOT gaps. The script's
    job is to catch *new* episodes the pipeline missed."""
    oneplace = [_ep(1281619)]
    archived = {"1281619", "664", "412", "411"}
    assert CAF.find_gap(oneplace, archived) == []


def test_find_gap_preserves_input_order():
    """find_gap is a filter, not a sort — the descending-by-eid order
    of its output comes from the caller (fetch_oneplace_recent already
    sorts). Asserting that contract here so a future "tidy up" doesn't
    silently re-sort and mask a fetch-side regression."""
    oneplace = [_ep(1281619), _ep(1281618), _ep(1281610), _ep(1281607)]
    gap = CAF.find_gap(oneplace, set())
    assert [e.episode_id for e in gap] == [1281619, 1281618, 1281610, 1281607]


def test_find_gap_empty_oneplace_means_no_gap():
    """Defensive: if oneplace returns nothing (transient API hiccup),
    the gap detector must NOT report 'everything missing' — that would
    cause false alarms on every transient outage."""
    assert CAF.find_gap([], {"1281619"}) == []


# ---------------------------------------------------------------------------
# oneplace fetch — bootstrap parse + payload walk
# ---------------------------------------------------------------------------


_LISTEN_HTML_REAL_SHAPE = (
    b'<html><script>var __NEXT_DATA__ = {"props":{"pageProps":{'
    b'"episodeId":"1281612","showId":"777"}}};</script></html>'
)


def test_bootstrap_eid_parsed_from_listen_html():
    def http_get(url, headers):
        assert "adventures-in-odyssey/listen/" in url
        return _LISTEN_HTML_REAL_SHAPE

    eid = CAF.fetch_oneplace_bootstrap_eid("adventures-in-odyssey", http_get=http_get)
    assert eid == 1281612


def test_bootstrap_eid_raises_when_regex_misses():
    def http_get(url, headers):
        return b"<html>no episode id here</html>"

    with pytest.raises(ValueError, match="bootstrap regex did not match"):
        CAF.fetch_oneplace_bootstrap_eid("adventures-in-odyssey", http_get=http_get)


def test_fetch_oneplace_recent_filters_by_show_id_and_dedups():
    """A real probe-forward walk surfaces the same AIO eid from
    multiple seed offsets (each page returns the related-episodes
    cluster). Dedup must happen on episodeId, and non-AIO showIds
    (e.g. Sekulow showId=663) must be dropped."""
    pages = {
        1281612: [
            {"showId": 777, "episodeId": 1281612, "title": "The Truth About Zachary",
             "subTitle": "June 25, 2026"},
            {"showId": 777, "episodeId": 1281611, "title": "I Want My B-TV!",
             "subTitle": "June 24, 2026"},
            {"showId": 663, "episodeId": 9999, "title": "Sekulow contaminant",
             "subTitle": "June 24, 2026"},
        ],
        1281613: [
            {"showId": 777, "episodeId": 1281613, "title": "Preacher's Kid",
             "subTitle": "June 26, 2026"},
            {"showId": 777, "episodeId": 1281612, "title": "The Truth About Zachary",
             "subTitle": "June 25, 2026"},
        ],
    }

    def http_get(url, headers):
        if "listen" in url:
            return _LISTEN_HTML_REAL_SHAPE
        from urllib.parse import urlparse, parse_qs
        eid = int(parse_qs(urlparse(url).query)["eid"][0])
        items = pages.get(eid, [])
        return json.dumps(items).encode()

    eps = CAF.fetch_oneplace_recent(
        "adventures-in-odyssey", show_id=777, probe_window=2, http_get=http_get,
    )
    assert [e.episode_id for e in eps] == [1281613, 1281612, 1281611]
    assert all(isinstance(e.air_date, str) for e in eps)
    assert all(e.episode_id != 9999 for e in eps), "Sekulow row leaked"


def test_fetch_oneplace_recent_tolerates_page_level_errors():
    """One bad page (HTTP 500, malformed JSON) must not abort the walk
    — the probe-forward strategy depends on covering 50 eids and a
    handful are normally gaps in the global id sequence."""
    from urllib.error import HTTPError

    def http_get(url, headers):
        if "listen" in url:
            return _LISTEN_HTML_REAL_SHAPE
        from urllib.parse import urlparse, parse_qs
        eid = int(parse_qs(urlparse(url).query)["eid"][0])
        if eid == 1281613:
            raise HTTPError(url, 500, "boom", {}, None)
        if eid == 1281614:
            return b"not json {{"
        return json.dumps([
            {"showId": 777, "episodeId": eid, "title": "ok",
             "subTitle": "Jun 1, 2026"},
        ]).encode()

    eps = CAF.fetch_oneplace_recent(
        "adventures-in-odyssey", show_id=777, probe_window=3, http_get=http_get,
    )
    # 1281612 (seed+0) + 1281615 (seed+3) survive; the two bad pages
    # are skipped without polluting the result with a phantom eid.
    assert {e.episode_id for e in eps} == {1281612, 1281615}


# ---------------------------------------------------------------------------
# NAS fetch — uses real archive-service response shape (EpisodeOutV2)
# ---------------------------------------------------------------------------


def test_fetch_nas_archived_eids_extracts_external_ids():
    rows = [
        {"external_id": "1278383", "provider_id": "aio", "title": "War of the Words"},
        {"external_id": "1278382", "provider_id": "aio", "title": "Making the Grade"},
    ]

    captured = {}

    def http_get(url, headers):
        captured["url"] = url
        captured["headers"] = headers
        return json.dumps(rows).encode()

    eids = CAF.fetch_nas_archived_eids(
        "http://nas:8088/", "TOKEN", "aio", 500, http_get=http_get,
    )
    assert eids == {"1278383", "1278382"}
    assert captured["url"] == "http://nas:8088/providers/aio/episodes?limit=500"
    assert captured["headers"]["Authorization"] == "Bearer TOKEN"


# ---------------------------------------------------------------------------
# main() exit codes — what CI / cron will actually consume
# ---------------------------------------------------------------------------


def _stub_fetchers(monkeypatch, oneplace_eids, archived_eids):
    """Replace both network fetchers with in-memory stubs."""
    monkeypatch.setattr(
        CAF, "fetch_oneplace_recent",
        lambda slug, sid, window, **kw: [
            _ep(e, title=f"ep-{e}") for e in oneplace_eids
        ],
    )
    monkeypatch.setattr(
        CAF, "fetch_nas_archived_eids",
        lambda url, tok, prov, lim, **kw: {str(e) for e in archived_eids},
    )


def test_main_exits_0_when_no_gap(monkeypatch, capsys):
    _stub_fetchers(monkeypatch, [1281619, 1281618], [1281619, 1281618, 1278383])
    code = CAF.main(["--nas-url", "http://nas", "--nas-token", "t"])
    assert code == 0
    assert "OK" in capsys.readouterr().out


def test_main_exits_1_when_gap_detected(monkeypatch, capsys):
    """Uncataloged-episode gap path: oneplace yields 13 fresh eids with
    no catalog match (the stub leaves catalog_number=None), so find_gap
    falls back to pure eid comparison. main() must exit 1 and name the
    missing eids. See test_find_gap_real_world_2026_06_26_state for the
    catalog-aware path."""
    oneplace_eids = list(range(1281619, 1281606, -1))  # 13 fresh AIO episodes
    nas_top = 1278383
    _stub_fetchers(monkeypatch, oneplace_eids, [nas_top, 664, 412])

    code = CAF.main(["--nas-url", "http://nas", "--nas-token", "t", "--no-catalog"])
    out = capsys.readouterr().out

    assert code == 1, f"gap of {len(oneplace_eids)} eids must trip nonzero exit"
    assert "GAP" in out
    assert "1281619" in out and "1281607" in out, "missing eids must be named in output"
    assert "1278383" in out, "newest NAS eid must be surfaced for triage"


def test_main_json_output_round_trips(monkeypatch, capsys):
    _stub_fetchers(monkeypatch, [1281619, 1281618], [1281618])
    code = CAF.main(["--nas-url", "http://nas", "--nas-token", "t", "--json"])
    payload = json.loads(capsys.readouterr().out)
    assert code == 1
    assert payload["gap_count"] == 1
    assert payload["gap"][0]["episode_id"] == 1281619
    assert payload["oneplace_count"] == 2
    assert payload["nas_count"] == 1


def test_main_returns_2_without_credentials(monkeypatch, capsys):
    monkeypatch.delenv("ODYSSEY_NAS_URL", raising=False)
    monkeypatch.delenv("ODYSSEY_NAS_TOKEN", raising=False)
    code = CAF.main([])
    assert code == 2
    assert "required" in capsys.readouterr().err


# ---------------------------------------------------------------------------
# Catalog aliasing — the re-broadcast false-positive defense
# ---------------------------------------------------------------------------


def _write_catalog(tmp_path, entries):
    """Write a minimal aio_catalog.json with (album_name, [(short, name), ...])."""
    payload = {"albums": [
        {"name": a, "episodes": [
            {"name": n, "shortName": s} for s, n in eps
        ]}
        for a, eps in entries
    ]}
    p = tmp_path / "aio_catalog.json"
    p.write_text(json.dumps(payload), encoding="utf-8")
    return p


def test_load_catalog_index_maps_titles_to_catalog_numbers(tmp_path):
    p = _write_catalog(tmp_path, [
        ("#23: Twists and Turns", [
            ("#298: I Want My B-TV!", "I Want My B-TV!"),
            ("#297: Blackbeard's Treasure", "Blackbeard's Treasure"),
        ]),
        ("#51: Take It From the Top", [
            ("#664: The Jubilee Singers, Part 1 of 3", "The Jubilee Singers, Part 1 of 3"),
            ("#665: The Jubilee Singers, Part 2 of 3", "The Jubilee Singers, Part 2 of 3"),
            ("#666a: BONUS! Music", "BONUS! The music of the Jubilee Singers"),
        ]),
    ])
    index = CAF.load_catalog_index(p)
    assert index["i want my b-tv!"] == "298"
    assert index["blackbeard's treasure"] == "297"
    assert index["the jubilee singers, part 2 of 3"] == "665"
    # Bonus-track suffix preserved verbatim — these have to round-trip
    # so the gap check can find them on the NAS under "#666a".
    assert index["bonus! the music of the jubilee singers"] == "666a"


def test_load_catalog_index_returns_empty_when_file_missing(tmp_path):
    assert CAF.load_catalog_index(tmp_path / "does-not-exist.json") == {}


def test_load_catalog_index_uses_shipped_catalog():
    """The script defaults --catalog-path to the bundled aio_catalog.json
    next to the script. If that file moves the default breaks silently —
    pin that the bundled file exists AND contains the entries we depend
    on for the regression cases below."""
    index = CAF.load_catalog_index(CAF.DEFAULT_CATALOG_PATH)
    assert index, "bundled aio_catalog.json must be loadable"
    assert index.get("i want my b-tv!") == "298"
    assert index.get("the jubilee singers, part 2 of 3") == "665"


def test_find_gap_treats_catalog_number_match_as_archived():
    """The 2026-06-26 regression: oneplace re-aired classic episode
    #298 under a new eid 1281611. NAS has the row under external_id=298
    (uploaded years ago). Old find_gap would have flagged 1281611 as
    missing — the catalog alias must prevent that."""
    rebroadcast = _ep(1281611, "I Want My B-TV!", cat="298")
    archived = {"298", "664", "412"}
    assert CAF.find_gap([rebroadcast], archived) == []


def test_find_gap_catches_real_miss_even_when_sibling_archived():
    """Jubilee Singers Part 1 (#664) is on the NAS, Part 2 (#665) is
    not. The script must flag #665 specifically — not silently roll
    the gap up to "Jubilee Singers" as a whole."""
    part1 = _ep(1281618, "The Jubilee Singers, Part 1 of 3", cat="664")
    part2 = _ep(1281619, "The Jubilee Singers, Part 2 of 3", cat="665")
    archived = {"664"}
    gap = CAF.find_gap([part2, part1], archived)
    assert [e.catalog_number for e in gap] == ["665"]


def test_find_gap_falls_back_to_eid_for_uncataloged_episode():
    """Brand-new episodes (not in the bundled catalog) are uploaded by
    the app under the oneplace eid. With no catalog_number the gap
    check must still find them via eid alone."""
    brand_new = _ep(1278383, "War of the Words", cat=None)
    assert CAF.find_gap([brand_new], {"1278383"}) == []
    assert CAF.find_gap([brand_new], {"298", "664"}) == [brand_new]


def test_find_gap_real_world_2026_06_26_state():
    """End-to-end pin of the live state on the day this gap-detector
    was written. Of the 9 oneplace eids the script reported as
    "missing" before the catalog fix, 8 were on the NAS as their
    AIO catalog # — only Jubilee Pt 2 (#665) was a real gap. This
    test feeds the exact same data and asserts the new behavior."""
    oneplace = [
        _ep(1281619, "The Jubilee Singers, Part 2 of 3", cat="665"),
        _ep(1281618, "The Jubilee Singers, Part 1 of 3", cat="664"),
        _ep(1281614, "The Good, the Bad & Butch", cat="301"),
        _ep(1281613, "Preacher's Kid", cat="300"),
        _ep(1281612, "The Truth About Zachary", cat="299"),
        _ep(1281611, "I Want My B-TV!", cat="298"),
        _ep(1281610, "Blackbeard's Treasure", cat="297"),
        _ep(1281609, "Red Wagons and Pink Flamingos", cat="296"),
        _ep(1281608, "Soaplessly Devoted", cat="295"),
    ]
    archived = {"664", "301", "300", "299", "298", "297", "296", "295", "1278383"}
    gap = CAF.find_gap(oneplace, archived)
    assert [e.catalog_number for e in gap] == ["665"], (
        "regression — only Jubilee Pt 2 should remain a real gap"
    )


def test_fetch_oneplace_recent_populates_catalog_number_from_index():
    """fetch_oneplace_recent is the one that joins the catalog onto
    each result; the gap check itself just reads the field."""
    pages = {
        1281612: [
            {"showId": 777, "episodeId": 1281611, "title": "I Want My B-TV!",
             "subTitle": "June 24, 2026"},
            {"showId": 777, "episodeId": 1281612, "title": "BrandNewEpisode2026",
             "subTitle": "June 25, 2026"},
        ],
    }

    def http_get(url, headers):
        if "listen" in url:
            return _LISTEN_HTML_REAL_SHAPE
        from urllib.parse import urlparse, parse_qs
        eid = int(parse_qs(urlparse(url).query)["eid"][0])
        return json.dumps(pages.get(eid, [])).encode()

    index = {"i want my b-tv!": "298"}
    eps = CAF.fetch_oneplace_recent(
        "adventures-in-odyssey", show_id=777, probe_window=0,
        http_get=http_get, catalog_index=index,
    )
    by_eid = {e.episode_id: e for e in eps}
    assert by_eid[1281611].catalog_number == "298"
    assert by_eid[1281612].catalog_number is None, "uncataloged title stays None"


def test_main_catalog_aware_only_flags_real_misses(monkeypatch, capsys, tmp_path):
    """Integration: --catalog-path wired through main(). NAS has 8 of
    9 re-broadcast episodes under their catalog #; only #665 (Jubilee
    Pt 2) is missing. Without the catalog, the script would have
    flagged all 9 — with it, exactly one."""
    catalog_path = _write_catalog(tmp_path, [
        ("#23", [(f"#{n}: T{n}", f"T{n}") for n in range(295, 302)]),
        ("#51", [
            ("#664: J1", "J1"),
            ("#665: J2", "J2"),
        ]),
    ])

    def fake_oneplace(slug, sid, window, **kw):
        idx = kw.get("catalog_index") or {}
        rows = [
            (1281619, "J2"),
            (1281618, "J1"),
            (1281614, "T301"),
            (1281613, "T300"),
            (1281612, "T299"),
            (1281611, "T298"),
            (1281610, "T297"),
            (1281609, "T296"),
            (1281608, "T295"),
        ]
        return [
            _ep(eid, title, cat=idx.get(title.lower()))
            for eid, title in rows
        ]

    monkeypatch.setattr(CAF, "fetch_oneplace_recent", fake_oneplace)
    monkeypatch.setattr(
        CAF, "fetch_nas_archived_eids",
        lambda *a, **k: {"664", "301", "300", "299", "298", "297", "296", "295"},
    )

    code = CAF.main([
        "--nas-url", "http://nas", "--nas-token", "t",
        "--catalog-path", str(catalog_path), "--json",
    ])
    payload = json.loads(capsys.readouterr().out)
    assert code == 1
    assert payload["gap_count"] == 1
    assert payload["gap"][0]["episode_id"] == 1281619
    assert payload["gap"][0]["catalog_number"] == "665"
    assert payload["catalog_loaded"] >= 9


def test_main_no_catalog_flag_disables_alias_lookup(monkeypatch, capsys, tmp_path):
    """--no-catalog forces eid-only mode, useful for proving a
    suspected pipeline regression is real and not just a catalog
    coverage hole."""
    catalog_path = _write_catalog(tmp_path, [
        ("#23", [("#298: I Want My B-TV!", "I Want My B-TV!")]),
    ])

    captured_index = {}

    def fake_oneplace(slug, sid, window, **kw):
        captured_index["idx"] = kw.get("catalog_index") or {}
        return [_ep(1281611, "I Want My B-TV!", cat=None)]

    monkeypatch.setattr(CAF, "fetch_oneplace_recent", fake_oneplace)
    monkeypatch.setattr(CAF, "fetch_nas_archived_eids", lambda *a, **k: {"298"})

    code = CAF.main([
        "--nas-url", "http://nas", "--nas-token", "t",
        "--catalog-path", str(catalog_path), "--no-catalog", "--json",
    ])
    payload = json.loads(capsys.readouterr().out)
    assert captured_index["idx"] == {}, "--no-catalog must skip catalog load"
    assert code == 1
    assert payload["gap_count"] == 1
    assert payload["catalog_loaded"] == 0
