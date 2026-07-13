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


# ---------------------------------------------------------------------------
# AIO matcher — credit-phrase anchors (2026-06-08 tightening)
# ---------------------------------------------------------------------------


def _cat(*titles_albums: tuple[str, str]) -> list:
    """Helper — build a list of CatalogEpisode from (title, album) pairs."""
    out = []
    for title, album in titles_albums:
        out.append(WT.CatalogEpisode(
            title=title, short=f"#?: {title}", album=album, number=None,
        ))
    return out


def test_backward_anchor_was_written_by_finds_real_title():
    """Real failure mode from the 2026-06-08 run: ep 19 announcer says
    'Buried Sin was written by John Beebe' — the matcher must pick up
    'Buried Sin' from the 12 words BEFORE 'was written by'."""
    catalog = _cat(
        ("Buried Sin", "#32: Hidden Treasures"),
        ("Recollections", "#32: Hidden Treasures"),
        ("Switch", "#22: The Changing Times"),
    )
    transcript = (
        "Adventures in Odyssey is a presentation of Focus on the Family. "
        "Buried Sin was written by John Beebe and directed by Bill Myers. "
        "Our production engineer was Jonathan Crowe. "
        "And I'm Chris, hoping you'll join us again next time."
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "Buried Sin"
    assert score == 1.0


def test_forward_anchor_today_s_episode_is_called():
    """'today's episode it's called Knox on Money' — the title follows
    the anchor; matcher takes next 12 words and substring-matches."""
    catalog = _cat(
        ("Knox on Money", "#81: Never a Dull Moment"),
        ("Choices", "#19: Passport To Adventure"),
    )
    transcript = (
        "thanks for tuning in to today s episode it s called knox on money "
        "the address again is odyssey colorado springs colorado"
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "Knox on Money"
    assert score == 1.0


def test_part_suffix_stripping_recognizes_base_title():
    """'A Touch of Healing was written…' should match either Part 1
    or Part 2 of the catalog entry, since announcers credit the BASE
    title without the Part suffix."""
    catalog = _cat(
        ("A Touch of Healing, Part 2 of 2", "#23: Twists and Turns"),
        ("Odyssey Sings!", "#41: A Sound Adventure"),
    )
    transcript = (
        "presentation of Focus on the Family. "
        "A Touch of Healing was written and directed by Paul McCusker. "
        "Our production engineer was Dave Arnold. "
        "And I'm Chris, hoping you'll join us again next time."
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "A Touch of Healing, Part 2 of 2"
    # Stripped substring hit scores 0.95.
    assert score >= 0.95


def test_anchor_kills_cast_name_substring_false_positive():
    """Real false positive from the 2026-06-08 run: 'Jay Karen Thomas'
    in a cast list let the matcher substring-hit 'Karen' across an
    unrelated episode. With anchor-first, 'Karen' must appear in the
    post-anchor span — cast lists come AFTER the writer/director credit
    and aren't anchored to a backward 'was written by' phrase."""
    catalog = _cat(
        ("Karen", "#03: Heroes"),
        ("No Way In, Part 2 of 2", "#37: Countermoves"),
    )
    transcript = (
        "no way in part 2 was written by paul mccusker. "
        "our vocal talent included Paul Herlinger, Katie Lee, Townsend Coleman, "
        "Mark Christopher Lawrence, Jay Karen Thomas, Jeff Doucette, Phil Crowley."
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    # The backward anchor 'was written by' anchors at 'no way in part 2'
    # which fuzzy/stripped matches 'No Way In, Part 2 of 2' — NOT 'Karen'.
    assert match is not None
    assert match.title != "Karen"
    assert "No Way In" in match.title


def test_anchor_kills_album_name_substring_false_positive():
    """Real false positive: 'in the Adventures in Odyssey album called
    Secrets, Surprises, and Sensational Stories' let 'Secrets' (a
    different episode's title) substring-hit. Anchor-first requires
    Secrets to appear AFTER an announcer credit phrase like 'it's
    called'; here the transcript references it as an album name only."""
    catalog = _cat(
        ("Secrets", "#42: No Way Out"),
        ("An Act of Nobility, Part 1 of 2", "#?: Other"),
    )
    transcript = (
        "the story behind which salvation can be heard in the episode called "
        "thank you god, available in the Adventures in Odyssey album called "
        "Secrets, Surprises, and Sensational Stories. ask how you can get your "
        "own copy by writing to Odyssey, Colorado Springs, Colorado 80995."
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    # No credit anchor ("was written by" / "today's episode is called")
    # fires, so matching falls to the unanchored tail pass — which now
    # skips single-word titles. "Secrets" must NOT come back as an
    # auto-propose (>= 0.95) off an incidental album-name mention.
    assert not (match is not None and match.title == "Secrets" and score >= 0.95), (
        f"single-word 'Secrets' auto-proposed off an album mention "
        f"(score={score}); the unanchored tail must not trust 1-word titles"
    )


def test_single_word_title_still_matches_when_anchored():
    """The single-word guard is scoped to the UNANCHORED tail — a real
    one-word title announced right after a credit anchor ('today's
    episode is called Karen') must still match at full confidence."""
    catalog = _cat(
        ("Karen", "#03: Heroes"),
        ("Knox on Money", "#81: Never a Dull Moment"),
    )
    transcript = (
        "well that s our story for today today s episode is called karen "
        "join us again next time on adventures in odyssey"
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "Karen"
    assert score == 1.0


def test_is_same_base_part_recognizes_part_only_diffs():
    """The matcher hits the BASE title for multi-part shows ('A Touch
    of Healing was written…'), but the catalog has separate entries
    per part. cmd_plan / cmd_apply must NOT propose swapping Part 3
    → Part 1 just because the matcher couldn't disambiguate from the
    base credit alone."""
    assert WT._is_same_base_part(
        "A Touch of Healing, Part 2 of 2",
        "A Touch of Healing, Part 1 of 2",
    )
    assert WT._is_same_base_part(
        "The Search for Whit, Part 3 of 3",
        "The Search for Whit, Part 1 of 3",
    )
    # Same title — not "different" so the helper returns False.
    assert not WT._is_same_base_part("Knox on Money", "Knox on Money")
    # Different bases — real rename candidate.
    assert not WT._is_same_base_part("Nothing to Fear", "Stage Fright")
    # Single-parter — no Part suffix to strip.
    assert not WT._is_same_base_part("Choices", "Knox on Money")


def test_fallback_tail_bias_still_works_when_no_anchor():
    """A bare-title closer ('Knox on Money. Adventures in Odyssey is…')
    bypasses the anchor patterns; legacy tail-bias fuzzy still picks
    up the title."""
    catalog = _cat(
        ("Knox on Money", "#81: Never a Dull Moment"),
        ("Choices", "#19: Passport To Adventure"),
    )
    transcript = (
        "and so the lesson was learned. Knox on Money. "
        "Adventures in Odyssey is a presentation of Focus on the Family. "
        "see you next time."
    )
    match, score = WT.best_catalog_match(transcript, catalog)
    assert match is not None
    assert match.title == "Knox on Money"
    assert score == 1.0


# ---------------------------------------------------------------------------
# ArchiveClient — unsorted-album 404 is a healthy empty state, not an error
# ---------------------------------------------------------------------------


def test_list_unsorted_returns_empty_on_404(monkeypatch):
    """A clean archive has no 'Unsorted' album, so the server 404s. That
    must surface as [] (nothing to validate), not an unhandled crash —
    the failure that stopped `validate --unsorted` in the field."""
    from urllib.error import HTTPError

    client = WT.ArchiveClient("http://x", "tok")

    def boom(method, path, **kw):
        raise HTTPError(path, 404, "no episodes for that album", {}, None)

    monkeypatch.setattr(client, "_req", boom)
    assert client.list_unsorted_episodes() == []


def test_list_unsorted_reraises_non_404(monkeypatch):
    """Only 404 is the benign sentinel — a real 500 must still propagate."""
    from urllib.error import HTTPError

    client = WT.ArchiveClient("http://x", "tok")

    def boom(method, path, **kw):
        raise HTTPError(path, 500, "kaboom", {}, None)

    monkeypatch.setattr(client, "_req", boom)
    with pytest.raises(HTTPError):
        client.list_unsorted_episodes()
