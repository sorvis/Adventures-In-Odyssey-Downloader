"""Unit tests for the pure-function pieces of scripts/whisper_titles.py.

The SSH/ffmpeg/whisperx transport isn't exercised here — those need a
live Proxmox host. The catalog parser and the fuzzy matcher are the
only places where wrong logic would silently corrupt episode titles,
so they're the ones worth pinning.
"""
from __future__ import annotations
import importlib.util
import sys
from pathlib import Path

import pytest


def _load_script():
    here = Path(__file__).resolve().parent.parent
    spec = importlib.util.spec_from_file_location(
        "whisper_titles", here / "scripts" / "whisper_titles.py"
    )
    mod = importlib.util.module_from_spec(spec)
    sys.modules["whisper_titles"] = mod
    spec.loader.exec_module(mod)
    return mod


WT = _load_script()


def test_parse_short_strips_number_prefix():
    n, t = WT._parse_short("#1030: Knox on Money")
    assert n == "1030"
    assert t == "Knox on Money"


def test_parse_short_without_prefix_returns_raw():
    n, t = WT._parse_short("Welcome Home")
    assert n is None
    assert t == "Welcome Home"


def test_best_match_exact_substring_wins_full_confidence(tmp_path):
    catalog = [
        WT.CatalogEpisode(title="Knox on Money", short="#1030: Knox on Money",
                          album="#81: Never a Dull Moment", number="1030"),
        WT.CatalogEpisode(title="What's the Catch?", short="#1036: What's the Catch?",
                          album="#81: Never a Dull Moment", number="1036"),
    ]
    transcript = (
        "thanks for listening to today's episode you've been listening to "
        "knox on money tune in next time"
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "Knox on Money"
    assert score == 1.0


def test_best_match_fuzzy_when_announcer_garbles_slightly():
    catalog = [
        WT.CatalogEpisode(title="The Lady of Longpoint", short="#42: The Lady of Longpoint",
                          album="#3: Heroes", number="42"),
        WT.CatalogEpisode(title="Knox on Money", short="#1030: Knox on Money",
                          album="#81: Never a Dull Moment", number="1030"),
    ]
    # Misheard: "of" → "ov", "Longpoint" → "Longpont"
    transcript = "join us next week as we continue with the lady ov longpont goodbye"
    match, score = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "The Lady of Longpoint"
    # Fuzzy — not 1.0 but well above noise.
    assert score > 0.7
    assert score < 1.0


def test_best_match_picks_tail_mention_not_earlier_mention():
    """Critical: if the announcer name-drops a different episode mid-show
    ("..just like in Knox on Money..."), the credit at the END for the
    REAL episode must still win. The sliding window is tail-biased so
    this is the codified behavior."""
    catalog = [
        WT.CatalogEpisode(title="Knox on Money", short="#1030: Knox on Money",
                          album="#81: Never a Dull Moment", number="1030"),
        WT.CatalogEpisode(title="Whats the Catch", short="#1036: What's the Catch?",
                          album="#81: Never a Dull Moment", number="1036"),
    ]
    # Knox is mentioned EARLY (a reference); Catch is the actual credit.
    transcript = (
        "remember the episode where eugene tried knox on money well today "
        "is a different lesson "
        + ("filler " * 30)
        + "you have been listening to whats the catch see you next time"
    )
    match, _ = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "Whats the Catch"


def test_best_match_returns_none_for_empty_inputs():
    m, s = WT.best_catalog_match("", [])
    assert m is None and s == 0.0
    m2, s2 = WT.best_catalog_match("anything", [])
    assert m2 is None and s2 == 0.0


def test_load_catalog_reads_shipped_aio_catalog():
    here = Path(__file__).resolve().parent.parent
    catalog = WT.load_catalog(here / "aio_catalog.json")
    assert len(catalog) > 100, "shipped catalog should expose at least 100 episodes"
    by_title = {e.title for e in catalog}
    # Smoke check: the two episodes from album 81 we already verified
    # by hand should be present.
    assert "Knox on Money" in by_title


def test_norm_lowercases_and_strips_punctuation():
    assert WT._norm("Knox on Money!") == "knox on money"
    assert WT._norm("What's the Catch?") == "what s the catch"


def test_best_ysh_match_finds_announced_title_in_head():
    """YSH credit fixture: 'I call my story, The Lady of Long Point' —
    the exact phrase the probe pulled off CT 112. Matcher should pick
    that title from a candidate set."""
    candidates = [
        "The Lady of Longpoint",
        "The Charming Prince",
        "Rags and Running Shoes",
    ]
    transcript = (
        "presenting your story hour building for a better tomorrow hi kids "
        "moms dads and grandparents we are glad you are back with us "
        "my story is about a canadian woman who lived back in the 1800s "
        "i call my story the lady of long point"
    )
    match, score = WT.best_ysh_match(transcript, candidates)
    # whisperx heard "Long Point" (2 words) but the catalog title is
    # "Longpoint" (1 word) — exercises the fuzzy fallback, not the
    # substring-exact branch.
    assert match == "The Lady of Longpoint"
    assert score > 0.8


def test_best_ysh_match_is_head_biased_not_tail():
    """A title-shaped phrase showing up at the END of the head window
    (or after it) should NOT beat one at the actual head."""
    candidates = ["The First Story", "The Last Story"]
    transcript = "i call my story the first story " + (" filler" * 100) + " the last story"
    match, _ = WT.best_ysh_match(transcript, candidates)
    assert match == "The First Story"


def test_best_ysh_match_returns_none_on_empty():
    assert WT.best_ysh_match("", ["X"]) == (None, 0.0)
    assert WT.best_ysh_match("anything", []) == (None, 0.0)
