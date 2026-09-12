"""Unit tests for the pure-function pieces of scripts/whisper_titles.py.

The SSH/ffmpeg/whisperx transport isn't exercised here — those need a
live Proxmox host. The catalog parser and the fuzzy matcher are the
only places where wrong logic would silently corrupt episode titles,
so they're the ones worth pinning.
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


# ---------------------------------------------------------------------------
# audit-ysh: provenance scoring
# ---------------------------------------------------------------------------


def test_score_markers_counts_nested_phrase_once():
    """"story hour" is a substring of "your story hour". A transcript
    with only the long form must score the long form's weight, not
    both — otherwise every normal episode inflates past the margin."""
    score, hits = WT.score_markers(
        "welcome to your story hour", WT._YSH_SHOW_MARKERS)
    assert hits == ["your story hour"]
    assert score == pytest.approx(3.0)


def test_provenance_confirms_ysh_from_station_id():
    p = WT.ysh_provenance(
        "this is Uncle Dan. you have been listening to Your Story Hour, "
        "from Berrien Springs, Michigan."
    )
    assert p.verdict == "ysh"
    assert p.foreign_score == 0.0


def test_provenance_flags_foreign_show():
    """A mis-ingested AIO episode is the whole reason provenance exists:
    it would score 0.0 against every YSH title, which on title evidence
    alone is indistinguishable from a garbled transcript."""
    p = WT.ysh_provenance(
        "you've been listening to Adventures in Odyssey, "
        "a production of Focus on the Family. Whit's End is open."
    )
    assert p.verdict == "foreign"
    assert p.ysh_score == 0.0


def test_provenance_unknown_when_no_station_id():
    """Silence/garble must never be reported as a mis-filed episode."""
    assert WT.ysh_provenance("and then they walked home together").verdict \
        == "unknown"
    assert WT.ysh_provenance("").verdict == "unknown"


def test_provenance_ambiguous_when_both_shows_tie():
    """Equal billing for both shows — a promo, a cross-mention, or two
    files concatenated. Neither verdict is safe, so a human listens."""
    p = WT.ysh_provenance(
        "this has been Your Story Hour... next up, Adventures in Odyssey"
    )
    assert p.verdict == "ambiguous"
    assert p.ysh_score == p.foreign_score


def test_provenance_prefers_the_side_with_more_evidence():
    """One passing mention of YSH does not save a clip carrying two
    independent AIO station IDs."""
    p = WT.ysh_provenance(
        "Your Story Hour is brought to you... Adventures in Odyssey "
        "is a production of Focus on the Family"
    )
    assert p.verdict == "foreign"


def test_provenance_single_word_overlap_does_not_vote():
    """Bare tokens are deliberately not markers — a YSH story may well
    say "odyssey" or name a character "Whit"."""
    p = WT.ysh_provenance("their odyssey across the sea took months")
    assert p.verdict == "unknown"


# ---------------------------------------------------------------------------
# audit-ysh: catalog + title variants
# ---------------------------------------------------------------------------


def test_sku_id_from_external_id():
    assert WT.sku_id_from_external_id("ysh-sku-447") == 447
    assert WT.sku_id_from_external_id("1278410") is None
    assert WT.sku_id_from_external_id(None) is None


def test_ysh_title_variants_drops_parenthetical_and_part():
    v = WT.ysh_title_variants("Child of Privilege (Lottie Moon Part 1)")
    assert v[0] == "Child of Privilege (Lottie Moon Part 1)"
    assert "Child of Privilege" in v


def test_load_ysh_catalog_accepts_flat_shape(tmp_path):
    p = tmp_path / "flat.json"
    p.write_text(json.dumps([
        {"sku_id": 447, "title": "The Lady of Longpoint",
         "album_title": "Great Stories - Volume 4"},
    ]))
    tracks = WT.load_ysh_catalog(p)
    assert tracks == [WT.YshTrack(447, "The Lady of Longpoint",
                                  "Great Stories - Volume 4")]


def test_load_ysh_catalog_accepts_scrape_shape(tmp_path):
    p = tmp_path / "ysh_catalog.json"
    p.write_text(json.dumps({"albums": [
        {"title": "Great Stories - Volume 4",
         "tracks": [{"sku_id": 447, "title": "The Lady of Longpoint"}]},
    ]}))
    assert WT.load_ysh_catalog(p) == [
        WT.YshTrack(447, "The Lady of Longpoint", "Great Stories - Volume 4")]


def test_load_ysh_catalog_rejects_unknown_shape(tmp_path):
    p = tmp_path / "junk.json"
    p.write_text('"not a catalog"')
    with pytest.raises(ValueError):
        WT.load_ysh_catalog(p)


# ---------------------------------------------------------------------------
# audit-ysh: teaser trimming + title verdicts
# ---------------------------------------------------------------------------


def test_trim_teaser_cuts_next_episode_plug():
    trimmed = WT.trim_teaser(
        "I call my story, The Land of Uz. ... be with us next week for "
        "Run for Your Life."
    )
    assert "land of uz" in trimmed
    assert "run for your life" not in trimmed


def test_trim_teaser_keeps_everything_when_no_teaser():
    assert WT.trim_teaser("this has been Your Story Hour") \
        == "this has been your story hour"


def test_title_verdict_confirms_own_title():
    v = WT.ysh_title_verdict(
        "hello boys and girls. I call my story, The Lady of Longpoint.",
        "The Lady of Longpoint",
        ["The Lady of Longpoint", "The Land of Uz"],
    )
    assert v.verdict == "match"
    assert v.own_score == pytest.approx(1.0)


def test_title_verdict_confirms_despite_parenthetical():
    """The storyteller announces the bare title; the catalog carries a
    disambiguator. That must not read as a mismatch."""
    v = WT.ysh_title_verdict(
        "I call my story, Child of Privilege.",
        "Child of Privilege (Lottie Moon Part 1)",
        ["Child of Privilege (Lottie Moon Part 1)", "The Land of Uz"],
    )
    assert v.verdict == "match"


def test_title_verdict_raises_anchored_mismatch():
    """The swap case the audit exists to catch: the row says one story,
    the announcer names another."""
    v = WT.ysh_title_verdict(
        "I call my story, The Land of Uz.",
        "The Lady of Longpoint",
        ["The Lady of Longpoint", "The Land of Uz"],
    )
    assert v.verdict == "mismatch"
    assert v.best_title == "The Land of Uz"
    assert v.anchored


def test_title_verdict_ignores_unanchored_dialogue_hit():
    """Regression: a catalog title made of ordinary words ("Run for Your
    Life") appearing as a line of dialogue scored 1.00 and accused a
    correctly-labeled episode of being the wrong story."""
    v = WT.ysh_title_verdict(
        "the dogs were closing in and she screamed run for your life",
        "A Light in the Window",
        ["A Light in the Window", "Run for Your Life"],
    )
    assert v.verdict == "inconclusive"
    assert not v.anchored


def test_title_verdict_weak_anchor_cannot_accuse():
    """"the story of" is ordinary narration, so a title found behind it
    is not localized evidence and must not raise a mismatch."""
    v = WT.ysh_title_verdict(
        "and that was the story of The Land of Uz as they told it",
        "The Lady of Longpoint",
        ["The Lady of Longpoint", "The Land of Uz"],
    )
    assert v.verdict == "inconclusive"


def test_title_verdict_requires_margin_over_own_title():
    """A rival that barely edges out the stored title is scoring noise
    between similar strings, not a swapped file."""
    v = WT.ysh_title_verdict(
        "I call my story, The Land of Uz",
        "The Land of Uz",
        ["The Land of Uz", "The Land of Us"],
        threshold=0.5,
    )
    assert v.verdict == "match"


def test_title_verdict_treats_part_suffix_swap_as_match():
    v = WT.ysh_title_verdict(
        "I call my story, A Touch of Healing, Part 1 of 2",
        "A Touch of Healing, Part 2 of 2",
        ["A Touch of Healing, Part 1 of 2", "A Touch of Healing, Part 2 of 2"],
    )
    assert v.verdict != "mismatch"


# ---------------------------------------------------------------------------
# audit-ysh: metadata cross-check + roll-up
# ---------------------------------------------------------------------------


CATALOG_BY_SKU = {
    447: WT.YshTrack(447, "The Lady of Longpoint", "Great Stories - Volume 4"),
}


def test_metadata_check_agrees_with_catalog():
    e = WT._ysh_metadata_check(
        {"episode_id": 1, "external_id": "ysh-sku-447",
         "title": "The Lady of Longpoint", "album": "Great Stories - Volume 4"},
        CATALOG_BY_SKU,
    )
    assert e.metadata_title_ok and e.metadata_album_ok
    assert e.clean          # no audio run yet, and metadata is fine


def test_metadata_check_flags_wrong_album():
    e = WT._ysh_metadata_check(
        {"episode_id": 1, "external_id": "ysh-sku-447",
         "title": "The Lady of Longpoint", "album": "Great Stories - Volume 9"},
        CATALOG_BY_SKU,
    )
    assert e.metadata_title_ok is True
    assert e.metadata_album_ok is False
    assert not e.clean


def test_metadata_check_unknown_sku_is_not_a_conflict():
    """A SKU minted after the catalog snapshot is a stale-catalog
    artifact; it must not masquerade as a title disagreement."""
    e = WT._ysh_metadata_check(
        {"episode_id": 1, "external_id": "ysh-sku-9999",
         "title": "Brand New Story", "album": "Whatever"},
        CATALOG_BY_SKU,
    )
    assert e.metadata_title_ok is None
    assert e.metadata_album_ok is None
    assert e.catalog_title is None


def test_audit_entry_not_clean_when_audio_disagrees():
    e = WT._ysh_metadata_check(
        {"episode_id": 1, "external_id": "ysh-sku-447",
         "title": "The Lady of Longpoint", "album": "Great Stories - Volume 4"},
        CATALOG_BY_SKU,
    )
    e.provenance = "foreign"
    assert not e.clean


def test_verdict_rank_prefers_confirmation_then_accusation():
    """Head and tail are scored independently; a confirmation from
    either settles the row, and an accusation must not be buried by an
    inconclusive clip whose own-title score edged it out."""
    match = WT.TitleVerdict("match", own_score=0.91, best_title="X",
                            best_score=0.91)
    mismatch = WT.TitleVerdict("mismatch", own_score=0.20, best_title="Y",
                               best_score=0.99, anchored=True)
    inconclusive = WT.TitleVerdict("inconclusive", own_score=0.60,
                                   best_title="Z", best_score=0.60)
    ranked = sorted([inconclusive, mismatch, match], key=WT._verdict_rank)
    assert [v.verdict for v in ranked] == ["inconclusive", "mismatch", "match"]


def test_matchable_rejects_titles_normalization_destroys():
    """`_norm` keeps only [a-z0-9 ], so a Cyrillic title collapses to ""
    or a bare volume digit."""
    assert not WT._matchable(WT._norm("Мария из Назарета"))
    assert not WT._matchable(WT._norm("Бриллиантовое Колье, ч. 2"))
    assert WT._matchable(WT._norm("The Land of Uz"))


def test_title_verdict_ignores_degenerate_catalog_entries():
    """Regression: the yourstoryhour.org catalog carries 182 Russian
    tracks. One of them normalized to "2", substring-matched a
    transcript at a perfect 1.00, and accused a correctly-labeled Paul
    Revere episode of being a Russian story."""
    v = WT.ysh_title_verdict(
        "I call my story, The Road to Revolution, part 2 of our series",
        "The Road to Revolution (Paul Revere Part 2)",
        ["The Road to Revolution (Paul Revere Part 2)",
         "Бриллиантовое Колье, ч. 2"],
    )
    assert v.verdict == "match"
    assert v.best_title != "Бриллиантовое Колье, ч. 2"


def test_audit_candidates_drops_unmatchable_titles():
    catalog = [
        WT.YshTrack(1, "The Land of Uz", "Bible Comes Alive - Album 4"),
        WT.YshTrack(2, "Судилище", "Russian Album"),
    ]
    assert WT._audit_candidates(catalog, ["The Lady of Longpoint"]) == [
        "The Lady of Longpoint", "The Land of Uz"]


def test_score_entry_is_reusable_offline():
    """`--from-report --rescore` must run the same scorer the live pass
    does, so a re-scored report can't drift from a fresh run."""
    e = WT.YshAuditEntry(
        episode_id=1, external_id="ysh-sku-1", title="The Land of Uz",
        album=None, sku_id=1, catalog_title=None, catalog_album=None,
        metadata_title_ok=None, metadata_album_ok=None,
    )
    e.head_transcript = "welcome to Your Story Hour. I call my story, The Land of Uz."
    e.tail_transcript = "this has been Your Story Hour from Berrien Springs."
    WT.score_entry(e, ["The Land of Uz", "The Lady of Longpoint"], {},
                   threshold=0.9)
    assert e.provenance == "ysh"
    assert e.title_verdict == "match"
    assert e.segment == "head"


# ---------------------------------------------------------------------------
# Matcher versioning
# ---------------------------------------------------------------------------


def test_matcher_version_is_per_provider():
    """Hardening the YSH matcher must not re-queue 361 AIO episodes."""
    assert WT.matcher_version("ysh") == WT.YSH_MATCHER_VERSION
    assert WT.matcher_version("aio") == WT.AIO_MATCHER_VERSION
    assert WT.matcher_version(None) == WT.AIO_MATCHER_VERSION
    assert WT.YSH_MATCHER_VERSION != WT.AIO_MATCHER_VERSION


def test_needs_recheck_for_never_validated_row():
    assert WT.needs_recheck({"provider_id": "ysh"})


def test_needs_recheck_false_at_current_version():
    assert not WT.needs_recheck({
        "provider_id": "ysh",
        "title_validated_at": "2026-09-11",
        "title_validator_version": WT.YSH_MATCHER_VERSION,
    })


def test_needs_recheck_true_for_pre_versioning_stamp():
    """The real case: 376 rows stamped 2026-06-08 carry a timestamp but
    no version, and were skipped by every run after the 2026-07-13
    matcher hardening. They must re-queue."""
    assert WT.needs_recheck({
        "provider_id": "aio",
        "title_validated_at": "2026-06-08",
        "title_validator_version": None,
    })


def test_needs_recheck_true_for_superseded_version():
    assert WT.needs_recheck({
        "provider_id": "ysh",
        "title_validated_at": "2026-09-11",
        "title_validator_version": "ysh/1",
    })
