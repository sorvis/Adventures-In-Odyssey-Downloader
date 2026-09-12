#!/usr/bin/env python3
"""
Validate, correct, and dedup episode titles using the home-lab whisper
instance on CT 112.

The user's pile of pre-import C# MP3s landed in the archive with titles
derived from filename heuristics — which means some episodes are
mis-labeled when the original filename was wrong or ambiguous. Adventures
in Odyssey announcers credit the episode title in the closing seconds
("...you've been listening to ___. Tune in next time."), so a 30-second
tail clip transcribed by whisperx is enough to recover the real title
without re-transcribing the whole episode.

Four subcommands:

  validate   For each candidate episode: GET its audio, ffmpeg-clip the
             tail, scp + pct push to CT 112, run whisperx, fuzzy-match
             the transcript against aio_catalog.json titles, write a
             JSON report. Read-only on the server.

  plan       Read a validate report and print (a) summary stats —
             scanned, errored, already-correct, below-threshold,
             proposed-changes, confidence histogram — and (b) the
             list of proposed (current_title → best_title) changes
             above --threshold. No server writes; safe to run anywhere.

  apply      Read a validate report and PATCH episodes whose match
             confidence exceeds --threshold and whose current title
             differs from the matched title.

  dedup      List episodes via the API, group by (title, album), report
             likely duplicates. With --delete-smaller the smaller of
             each pair is removed via DELETE /episodes/{id}.

  audit-ysh  Full-sweep audit of the YSH library, answering two
             questions per episode: (1) does the audio actually belong
             to Your Story Hour — scored off station-ID phrases, so a
             mis-ingested AIO episode is detected even when its title
             matches nothing; (2) does the announced story title agree
             with the row's title, scored against the full 1055-track
             yourstoryhour.org catalog. Also cross-checks every row's
             title/album against the catalog entry for its
             `ysh-sku-<id>` external_id, which needs no audio at all.
             Read-only: it reports, it never PATCHes.

Run from anywhere with: (a) network access to the archive-service,
(b) ssh access to the Proxmox host that owns CT 112, (c) ffmpeg on PATH.

Examples:
  scripts/whisper_titles.py validate \\
      --base-url http://192.168.2.142:8088 \\
      --token "$(cat ~/.aio-archive-token)" \\
      --pve root@192.168.2.123 \\
      --limit 20 \\
      --out /tmp/whisper-report.json

  scripts/whisper_titles.py apply --report /tmp/whisper-report.json \\
      --base-url http://192.168.2.142:8088 --token "$TOKEN" \\
      --threshold 0.85

  scripts/whisper_titles.py dedup \\
      --base-url http://192.168.2.142:8088 --token "$TOKEN" \\
      --delete-smaller
"""
from __future__ import annotations
import argparse
import difflib
import itertools
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Iterable
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


# ---------------------------------------------------------------------------
# Catalog loading + title normalization
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class CatalogEpisode:
    title: str       # "Knox on Money"
    short: str       # "#1030: Knox on Money"
    album: str       # "#81: Never a Dull Moment"
    number: str | None  # "1030" parsed from shortName when present


def _parse_short(s: str) -> tuple[str | None, str]:
    """`#1030: Knox on Money` → ("1030", "Knox on Money").
    Falls back to (None, s) when the prefix is absent."""
    m = re.match(r"^#?(\d+):\s*(.+)$", s.strip())
    if m:
        return m.group(1), m.group(2).strip()
    return None, s.strip()


def load_catalog(path: Path) -> list[CatalogEpisode]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    out: list[CatalogEpisode] = []
    for album in raw.get("albums", []):
        album_name = album.get("name", "")
        for ep in album.get("episodes", []):
            name = ep.get("name", "").strip()
            short = ep.get("shortName", name).strip()
            number, _ = _parse_short(short)
            out.append(
                CatalogEpisode(
                    title=name,
                    short=short,
                    album=album_name,
                    number=number,
                )
            )
    return out


_NORMALIZE_RE = re.compile(r"[^a-z0-9\s]")


def _norm(s: str) -> str:
    return _NORMALIZE_RE.sub(" ", s.lower()).strip()


# ---------------------------------------------------------------------------
# Matcher versioning
# ---------------------------------------------------------------------------
#
# A bare `title_validated_at` timestamp can't answer the only question a
# re-run actually has: "was this row checked by the CURRENT matcher?"
# That gap already cost us — 376 rows stamped 2026-06-08 were skipped by
# every later run even though the matcher was hardened on 2026-07-13, so
# the improvement never reached them.
#
# Versioned PER PROVIDER because the two matchers are independent code
# paths: hardening the YSH matcher shouldn't re-queue 361 AIO episodes.
# Bump the relevant constant whenever scoring behavior changes; rows
# stamped with an older (or NULL) version are re-checked automatically.
AIO_MATCHER_VERSION = "aio/1"
# ysh/2 — 2026-09-11: anchored-only mismatches, teaser trimming, margin
# over the row's own title, and the _matchable() candidate filter.
YSH_MATCHER_VERSION = "ysh/2"


def matcher_version(provider: str | None) -> str:
    return (YSH_MATCHER_VERSION if (provider or "").lower() == "ysh"
            else AIO_MATCHER_VERSION)


def needs_recheck(ep: dict) -> bool:
    """True when a row has never been validated, or was validated by a
    matcher older than the one running now."""
    if not ep.get("title_validated_at"):
        return True
    return ep.get("title_validator_version") != matcher_version(
        ep.get("provider_id"))


# When a YSH head probe scores below this, the validator does a
# second whisperx pass on the TAIL of the same episode. Picked to
# match the cleaned-up threshold (0.95) minus a margin — a head score
# >= 0.85 already gives us a strong-enough signal that we don't burn
# the extra GPU pass.
YSH_TAIL_FALLBACK_THRESHOLD = 0.85


# YSH host convention: the storyteller closes the cold-open dialogue with
# one of these credit phrases right before launching the actual story.
# Captured from real first-60s transcripts on 2026-06-07. Listed in
# order of specificity — the first match wins. Add new variants here
# as they're observed; the script logs anchor misses to stderr so the
# operator can grep transcripts for the missing phrase.
_YSH_CREDIT_ANCHORS = (
    "i call my story",
    "today my story is called",
    "today my story is",
    "my story today is called",
    "my story today is",
    "my story is called",
    "my story is",
    "which i call",                 # ep 1278411: "...which I call... Elizabeth"
    "our story for today",          # ep 1278411 long-form variant
    "today s story",                # "today's story" → normalized
    "the title of your story",      # ep 1278415: "the title of your story…"
    "the title of my story",
    "today we hear the story of",
    "the story of",                 # last-resort; weak signal
)

# Anchors too generic to localize a title. They still get scanned (a
# weak anchor beats no anchor when all you want is a confirmation of
# the row's own title), but a hit behind one of these never counts as
# "anchored", so it can never be the basis for accusing a row of
# holding the wrong story. "the story of" shows up in ordinary
# narration constantly.
_YSH_WEAK_ANCHORS = frozenset({"the story of"})


# A candidate title is only usable if normalization left enough of it to
# identify anything. `_norm` keeps [a-z0-9 ] and drops everything else,
# so the 182 Russian-language tracks in the yourstoryhour.org catalog
# collapse to "" or to a bare volume digit — and a candidate normalizing
# to "2" substring-matches almost every transcript at a perfect 1.00.
# That is exactly how a correctly-labeled Paul Revere episode got
# accused of being a Russian track. Unmatchable candidates are dropped
# rather than scored.
_MIN_MATCHABLE_CHARS = 4


def _matchable(normalized: str) -> bool:
    """True when a normalized candidate carries enough signal to match
    on: at least one ASCII letter and >= 4 alphanumeric characters."""
    if not normalized:
        return False
    compact = normalized.replace(" ", "")
    return (len(compact) >= _MIN_MATCHABLE_CHARS
            and any(c.isalpha() for c in compact))


def best_ysh_match(
    transcript: str,
    candidates: list[str],
) -> tuple[str | None, float]:
    """Score-only view of `best_ysh_match_detailed`. Kept as the
    original two-tuple signature because `validate` and its tests call
    it; only `audit-ysh` needs to know whether the hit was anchored."""
    title, score, _ = best_ysh_match_detailed(transcript, candidates)
    return title, score


def best_ysh_match_detailed(
    transcript: str,
    candidates: list[str],
) -> tuple[str | None, float, bool]:
    """Anchor-first matcher for YSH episodes.

    Third element is `anchored`: True when the title was found right
    after a credit phrase, False when it came from the unanchored
    sliding-window fallback. That distinction matters a lot — an
    unanchored hit is just "these words appear somewhere in 90
    seconds of story audio", which for a plain-English title like
    "Run for Your Life" is far more likely to be a line of dialogue
    than a title announcement.

    Strategy:
      1. Scan the transcript for one of the YSH credit anchors (e.g.
         "I call my story, X"). When found, fuzzy-match the next ~12
         words against the candidate set — exact substring scores 1.0,
         otherwise SequenceMatcher.ratio() on the whole tail.
      2. If no anchor fires, fall back to sliding-window fuzzy over
         the first 200 words. This covers older YSH formats and any
         show where the host phrasing wandered.

    Candidate set is the list of distinct YSH titles already in the
    archive — enough to spot if sku-447's audio is actually announcing
    what sku-559 is labeled as.
    """
    if not transcript or not candidates:
        return None, 0.0, False
    tn = _norm(transcript)

    # Anchor pass — strong signal, short scan.
    for anchor in _YSH_CREDIT_ANCHORS:
        idx = tn.find(anchor)
        if idx == -1:
            continue
        suffix_words = tn[idx + len(anchor):].split()[:12]
        if not suffix_words:
            continue
        suffix_str = " ".join(suffix_words)
        best: tuple[str | None, float] = (None, 0.0)
        for title in candidates:
            cn = _norm(title)
            if not _matchable(cn):
                continue
            if cn in suffix_str:
                # Substring within the post-anchor span — gold.
                if 1.0 > best[1] or (best[0] and len(cn) > len(_norm(best[0]))):
                    best = (title, 1.0)
                continue
            # Try a sliding-window fuzzy in case the announced title
            # has small word-count drift vs. catalog ("Long Point" vs.
            # "Longpoint", "Part 1" vs. "Part One", etc.).
            cn_words = cn.split()
            if not cn_words or len(cn_words) > len(suffix_words):
                continue
            for i in range(len(suffix_words) - len(cn_words) + 1):
                window = " ".join(suffix_words[i : i + len(cn_words)])
                r = difflib.SequenceMatcher(None, window, cn).ratio()
                if r > best[1]:
                    best = (title, r)
            # Also score the full suffix vs. the title — picks up cases
            # where the title is verbosely announced ("...The Lady of
            # Long Point in Canada").
            r_full = difflib.SequenceMatcher(None, suffix_str, cn).ratio()
            if r_full > best[1]:
                best = (title, r_full)
        if best[0] is not None:
            return best[0], best[1], anchor not in _YSH_WEAK_ANCHORS

    # Fallback: anchor not found → wider head scan. Older YSH cuts
    # sometimes use a music intro that obscures the credit line.
    tn_words = tn.split()
    head = tn_words[:200]
    head_str = " ".join(head)
    best = (None, 0.0)
    for title in candidates:
        cn = _norm(title)
        if not _matchable(cn):
            continue
        if cn in head_str:
            if 1.0 > best[1] or (best[0] is None or len(cn) > len(_norm(best[0]))):
                best = (title, 1.0)
            continue
        cn_words = cn.split()
        if not cn_words or len(cn_words) > len(head):
            continue
        max_local = 0.0
        for i in range(0, len(head) - len(cn_words) + 1):
            window = " ".join(head[i : i + len(cn_words)])
            r = difflib.SequenceMatcher(None, window, cn).ratio()
            if r > max_local:
                max_local = r
        if max_local > best[1]:
            best = (title, max_local)
    return best[0], best[1], False


def _ysh_candidates_from_archive(client: "ArchiveClient") -> list[str]:
    """Walk every episode in the archive, collect the distinct titles
    of YSH rows. Used as the candidate set for best_ysh_match — there's
    no shipped ysh_catalog.json today, but the existing YSH titles in
    the archive are a sufficient catalog for swap-detection (the audio
    for sku-447 should announce SOME title that another YSH row holds,
    not random noise)."""
    seen: set[str] = set()
    for ep in client.list_all_episodes():
        if (ep.get("provider_id") or "").lower() == "ysh":
            t = (ep.get("title") or "").strip()
            if t:
                seen.add(t)
    return sorted(seen)


# ---------------------------------------------------------------------------
# YSH provenance — "does this audio actually belong to Your Story Hour?"
# ---------------------------------------------------------------------------
#
# Title matching answers "is the row labeled with the right story?".
# It cannot answer "is this a YSH story at all?" — a mis-ingested AIO
# episode sitting under a `ysh-sku-*` external_id would simply score
# 0.0 against every YSH candidate, which is indistinguishable from a
# YSH episode whose credit line whisperx garbled.
#
# So provenance is scored separately, off show-branding phrases that
# appear in the intro/outro of essentially every broadcast. Phrases,
# never bare tokens: a YSH story about Greek myth can say "odyssey"
# and a YSH narrator can say "whit" as a name fragment. Only multi-word
# station-identification phrases are specific enough to vote.
#
# Weights are "how much does hearing this once convince me". Strongest
# first — `score_markers` consumes each hit from the working text so a
# weaker marker nested inside a stronger one ("story hour" inside
# "your story hour") can't double-count.

_YSH_SHOW_MARKERS: tuple[tuple[str, float], ...] = (
    ("your story hour", 3.0),
    ("yourstoryhour org", 3.0),
    ("your story hour inc", 3.0),
    ("berrien springs", 2.0),       # YSH's home town, read in the outro
    ("uncle dan", 1.5),
    ("aunt carole", 1.5),
    ("aunt sue", 1.5),
    ("uncle bob", 1.0),
    ("miss tracy", 1.0),
    ("story hour", 1.0),            # weaker standalone fallback
)

# Anything here means the audio is some OTHER show. AIO is the only
# realistic contaminant (it's the archive's other provider), but the
# list is open-ended by design.
_FOREIGN_SHOW_MARKERS: tuple[tuple[str, float], ...] = (
    ("adventures in odyssey", 3.0),
    ("focus on the family", 2.5),
    ("whit s end", 2.5),
    ("mr whittaker", 1.5),
    ("john avery whittaker", 2.0),
    ("odyssey usa", 1.5),
)

# Minimum winning score before we'll call provenance either way. Below
# this the transcript just didn't carry a station ID (music-only open,
# whisperx returned noise) — that's "unknown", not "foreign".
PROVENANCE_MIN_SCORE = 1.5
# How far ahead the winner must be before the verdict is confident.
# Both sides scoring similarly = "ambiguous" → human listens.
PROVENANCE_MARGIN = 1.5


def score_markers(
    transcript: str,
    markers: tuple[tuple[str, float], ...],
) -> tuple[float, list[str]]:
    """Sum the weights of every marker phrase present in `transcript`.

    Each hit is removed from the working copy before the next (weaker)
    marker is tested, so nested phrases score once: a transcript
    containing "your story hour" scores 3.0, not 3.0 + 1.0 for the
    "story hour" substring riding along inside it.

    Returns (score, [matched phrases in weight order]).
    """
    if not transcript:
        return 0.0, []
    tn = _norm(transcript)
    score = 0.0
    hits: list[str] = []
    for phrase, weight in markers:
        if phrase and phrase in tn:
            score += weight
            hits.append(phrase)
            tn = tn.replace(phrase, " ")
    return score, hits


@dataclass
class Provenance:
    verdict: str            # "ysh" | "foreign" | "ambiguous" | "unknown"
    ysh_score: float
    foreign_score: float
    ysh_hits: list[str]
    foreign_hits: list[str]


def ysh_provenance(transcript: str) -> Provenance:
    """Classify whose show this audio is, from station-ID phrases.

      ysh        — YSH branding present and clearly ahead. Row belongs.
      foreign    — another show's branding is clearly ahead. Row is
                   mis-ingested; it should not be in the YSH library.
      ambiguous  — both shows' branding scored close (a promo, a
                   cross-mention, or a bad concatenation of two files).
      unknown    — neither side cleared PROVENANCE_MIN_SCORE. Usually a
                   thin or garbled transcript, NOT evidence of a problem.

    Deliberately conservative: "unknown" is the default so a whisperx
    hiccup never gets reported as a mis-filed episode.
    """
    y, yh = score_markers(transcript, _YSH_SHOW_MARKERS)
    f, fh = score_markers(transcript, _FOREIGN_SHOW_MARKERS)
    if max(y, f) < PROVENANCE_MIN_SCORE:
        verdict = "unknown"
    elif y >= f + PROVENANCE_MARGIN:
        verdict = "ysh"
    elif f >= y + PROVENANCE_MARGIN:
        verdict = "foreign"
    else:
        verdict = "ambiguous"
    return Provenance(verdict, y, f, yh, fh)


# ---------------------------------------------------------------------------
# YSH catalog (yourstoryhour.org) — the title/album source of truth
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class YshTrack:
    sku_id: int | None
    title: str
    album: str


def load_ysh_catalog(path: Path) -> list[YshTrack]:
    """Load the YSH track catalog from either shipped shape:

      * `docs/ysh-probe/yourstoryhour-tracks-flat.json` — a flat list of
        {sku_id, title, album_title, ...} (1055 tracks).
      * `/srv/ysh_catalog.json` as written by `app.scrape_ysh` —
        {"albums": [{"title", "tracks": [{"sku_id", "title"}]}]}.

    Returning a flat track list either way keeps callers shape-agnostic.
    """
    raw = json.loads(path.read_text(encoding="utf-8"))
    out: list[YshTrack] = []
    if isinstance(raw, dict) and "albums" in raw:
        for album in raw.get("albums", []):
            album_title = (album.get("title") or album.get("name") or "").strip()
            for tr in album.get("tracks", []):
                title = (tr.get("title") or "").strip()
                if title:
                    out.append(YshTrack(tr.get("sku_id"), title, album_title))
        return out
    if isinstance(raw, list):
        for tr in raw:
            if not isinstance(tr, dict):
                continue
            title = (tr.get("title") or "").strip()
            if title:
                out.append(YshTrack(
                    tr.get("sku_id"),
                    title,
                    (tr.get("album_title") or tr.get("album") or "").strip(),
                ))
        return out
    raise ValueError(f"unrecognized YSH catalog shape in {path}")


_SKU_RE = re.compile(r"ysh-sku-(\d+)$", re.IGNORECASE)


def sku_id_from_external_id(external_id: str | None) -> int | None:
    """`ysh-sku-447` → 447. None when the row doesn't use the SKU
    scheme (oneplace-sourced rows keep their numeric oneplace id)."""
    if not external_id:
        return None
    m = _SKU_RE.search(external_id.strip())
    return int(m.group(1)) if m else None


_PAREN_RE = re.compile(r"\s*\([^)]*\)\s*")


def ysh_title_variants(title: str) -> list[str]:
    """Announcer-plausible renderings of a catalog title, longest first.

    YSH catalog titles carry disambiguators the storyteller never says
    out loud: "Child of Privilege (Lottie Moon Part 1)" is announced as
    just "Child of Privilege". Matching only the full string would score
    every multi-part story as a mismatch.
    """
    base = title.strip()
    variants = [base]
    no_paren = _PAREN_RE.sub(" ", base).strip(" ,-")
    if no_paren and no_paren not in variants:
        variants.append(no_paren)
    no_part = _strip_part_suffix(no_paren or base).strip(" ,-")
    if no_part and no_part not in variants:
        variants.append(no_part)
    return variants


def _variant_index(titles: Iterable[str]) -> dict[str, str]:
    """variant string → canonical catalog title. Longer variants win a
    collision so "Child of Privilege (Lottie Moon Part 1)" isn't
    shadowed by a bare "Child of Privilege" from another album."""
    idx: dict[str, str] = {}
    for t in titles:
        for v in ysh_title_variants(t):
            if not _matchable(_norm(v)):
                continue
            prev = idx.get(v)
            if prev is None or len(t) > len(prev):
                idx[v] = t
    return idx


# A YSH outro trails the station ID with a plug for the NEXT episode
# ("...be with us next week for <other title>"). Matching titles in
# that region reliably names the wrong episode — the one we're about
# to hear, not the one we just heard. Everything from the first teaser
# phrase onward is dropped before a tail clip is title-matched.
_TEASER_ANCHORS = (
    "next time",
    "next week",
    "join us next",
    "join us again",
    "be with us next",
    "be with us again",
    "tune in next",
    "coming up next",
    "our next story",
    "next story",
)


def trim_teaser(transcript: str) -> str:
    """Return `transcript` normalized and cut at the first
    next-episode teaser phrase. Returns the whole (normalized)
    transcript when no teaser is present."""
    tn = " ".join(_norm(transcript).split())
    cut = len(tn)
    for anchor in _TEASER_ANCHORS:
        idx = tn.find(anchor)
        if idx != -1:
            cut = min(cut, idx)
    return tn[:cut].strip()


@dataclass
class TitleVerdict:
    verdict: str              # "match" | "mismatch" | "inconclusive"
    own_score: float
    best_title: str | None
    best_score: float
    best_album: str | None = None
    anchored: bool = False    # was `best_title` found after a credit phrase?


def ysh_title_verdict(
    transcript: str,
    current_title: str,
    candidates: Iterable[str],
    *,
    threshold: float = 0.9,
    margin: float = 0.15,
    album_of: dict[str, str] | None = None,
) -> TitleVerdict:
    """Does the announced title agree with the row's stored title?

    Two independent scores rather than one global argmax:

      own_score  — the row's OWN title (and its announcer variants)
                   matched against the transcript.
      best_score — the whole candidate catalog matched against it.

    A row is CONFIRMED as soon as its own title clears `threshold`,
    even if some other catalog title scores marginally higher — near-
    duplicate titles across 1055 tracks make a bare argmax far too
    trigger-happy.

    Accusing a row of holding the WRONG story is held to a stricter
    standard still. The rival title must clear all three of:

      * `threshold` — the same bar a confirmation has to clear;
      * ANCHORED — found directly after a real credit phrase ("I call
        my story, X"), not merely somewhere in 90 seconds of story
        audio. Without this rule any title made of ordinary words
        fires on dialogue: the case that motivated it was a story
        about a flight to freedom scoring 1.00 for the catalog title
        "Run for Your Life" because a character shouts the phrase;
      * `margin` over the row's own score — when the rival barely
        edges out the stored title, that's scoring noise between two
        similar strings, not a swapped file.

    Everything else is inconclusive, which means "a human should
    listen", not "it's wrong".
    """
    cand_list = [c for c in candidates if c and c.strip()]
    own_score = 0.0
    if current_title:
        _, own_score, _ = best_ysh_match_detailed(
            transcript, ysh_title_variants(current_title))
    idx = _variant_index(cand_list)
    best_variant, best_score, anchored = best_ysh_match_detailed(
        transcript, sorted(idx))
    best_title = idx.get(best_variant) if best_variant else None
    best_album = (album_of or {}).get(best_title or "", None)

    if own_score >= threshold:
        verdict = "match"
    elif (best_title
          and anchored
          and best_score >= threshold
          and best_score >= own_score + margin
          and _norm(best_title) != _norm(current_title)
          and not _is_same_base_part(current_title, best_title)):
        verdict = "mismatch"
    else:
        verdict = "inconclusive"
    return TitleVerdict(
        verdict=verdict,
        own_score=own_score,
        best_title=best_title,
        best_score=best_score,
        best_album=best_album,
        anchored=anchored,
    )


# AIO closing-credit anchors observed in real transcripts (2026-06-08).
# Forward = title FOLLOWS the anchor ("today's episode is called X").
# Backward = title PRECEDES the anchor ("X was written by Y").
# Order = specificity, first match wins per direction.
_AIO_FORWARD_ANCHORS = (
    "today s episode is called",
    "today s episode it s called",
    "today s episode is",
    "today s episode",
    "you have been listening to",
    "you ve been listening to",
    "it s called",
)

_AIO_BACKWARD_ANCHORS = (
    "was written and directed by",
    "was written by",
    "was directed by",
)


def _strip_part_suffix(title: str) -> str:
    """'A Touch of Healing, Part 2 of 2' → 'A Touch of Healing'.

    Announcers usually drop the part-suffix when crediting a multi-part
    episode at the close, so the catalog's full title ('… Part 2 of 2')
    won't substring-match the credit. Stripping the suffix lets the
    matcher recognize the announced base title against either Part 1
    or Part 2 (both legitimate matches when the row's current title
    contains the same base)."""
    return re.sub(
        r",?\s*part\s+\d+\s+of\s+\d+\s*$",
        "",
        title,
        flags=re.IGNORECASE,
    ).strip()


def best_catalog_match(
    transcript: str,
    catalog: list[CatalogEpisode],
) -> tuple[CatalogEpisode | None, float]:
    """Score every catalog title against the transcript tail; return
    the best (entry, ratio in [0, 1]).

    Strategy:
      1. Substring win — if the normalized catalog title appears in the
         normalized transcript verbatim, that's a 1.0 match. Common case
         when the announcer reads it cleanly.
      2. Sliding fuzzy match — for each catalog title, take a window of
         the same word-count from the END of the transcript (the credit
         is always last) and run difflib.SequenceMatcher. Return the
         max ratio. Tail-focused so a stray earlier mention doesn't
         outscore the actual credit.
    """
    if not transcript or not catalog:
        return None, 0.0
    tn = _norm(transcript)
    tn_words = tn.split()

    # Pre-build (entry, normalized_full, normalized_stripped) tuples so
    # we don't re-normalize ~1200 catalog titles inside each anchor pass.
    indexed: list[tuple[CatalogEpisode, str, str]] = []
    for entry in catalog:
        cn = _norm(entry.title)
        stripped = _norm(_strip_part_suffix(entry.title))
        if cn:
            indexed.append((entry, cn, stripped))

    def _score_against(
        span_words: list[str],
        min_words: int = 1,
    ) -> tuple[CatalogEpisode | None, float]:
        """Match every catalog entry against `span_words`. Substring
        in the span scores 1.0 (full) / 0.95 (stripped); otherwise we
        slide a window of len(target words) and take the best fuzzy
        ratio.

        `min_words` skips catalog titles shorter than N words. The
        anchored passes call with 1 (a localized single-word title like
        "Karen" is trustworthy right after "it's called"), but the
        unanchored tail fallback passes 2: a bare word like "Secrets"
        substring-hitting an album-name mention
        ("…the album called Secrets, Surprises…") would otherwise score
        1.0 and auto-propose a bogus rename. Multi-word titles are
        specific enough to survive the unanchored tail."""
        if not span_words:
            return (None, 0.0)
        span = " ".join(span_words)
        local_best: tuple[CatalogEpisode | None, float] = (None, 0.0)
        for entry, full, stripped in indexed:
            if len(full.split()) >= min_words and full in span:
                score = 1.0
                if (score > local_best[1]
                        or (score == local_best[1]
                            and (local_best[0] is None
                                 or len(full) > len(_norm(local_best[0].title))))):
                    local_best = (entry, score)
                continue
            if (stripped and stripped != full
                    and len(stripped.split()) >= min_words
                    and stripped in span):
                if 0.95 > local_best[1]:
                    local_best = (entry, 0.95)
                continue
            # Sliding-window fuzzy on whichever of (full, stripped) fits.
            for target in (full, stripped):
                if not target or target == "":
                    continue
                tw = target.split()
                if not tw or len(tw) < min_words or len(tw) > len(span_words):
                    continue
                for i in range(len(span_words) - len(tw) + 1):
                    window = " ".join(span_words[i : i + len(tw)])
                    r = difflib.SequenceMatcher(None, window, target).ratio()
                    if r > local_best[1]:
                        local_best = (entry, r)
        return local_best

    # Pass 1: forward anchors. After "today's episode is called …" /
    # "you have been listening to …" / "it's called …" the next ~12
    # words are the title.
    for anchor in _AIO_FORWARD_ANCHORS:
        idx = tn.find(anchor)
        if idx == -1:
            continue
        suffix_words = tn[idx + len(anchor):].split()[:12]
        if not suffix_words:
            continue
        match = _score_against(suffix_words)
        # Anchor hits are high-signal; accept them at >= 0.7 fuzzy.
        # The 0.95 threshold in `plan` / `apply` still gates real
        # rename proposals.
        if match[0] is not None and match[1] >= 0.7:
            return match

    # Pass 2: backward anchors. Before "X was written by Y" / "X was
    # written and directed by Y", the immediately preceding ~12 words
    # contain X. This is the strongest signal in AIO closing credits.
    for anchor in _AIO_BACKWARD_ANCHORS:
        idx = tn.find(anchor)
        if idx == -1:
            continue
        prefix_words = tn[:idx].split()[-12:]
        if not prefix_words:
            continue
        match = _score_against(prefix_words)
        if match[0] is not None and match[1] >= 0.7:
            return match

    # Pass 3: legacy fallback — sliding-window over the last 50 words.
    # Catches title-drops that bypass the credit phrasing (older
    # episodes whose closer is just "<title>. Adventures in Odyssey…").
    # min_words=2: without an anchor to localize the title, single-word
    # catalog titles collide with incidental mentions (album names, cast
    # surnames) and must not auto-propose.
    tail = tn_words[-50:]
    return _score_against(tail, min_words=2)


# ---------------------------------------------------------------------------
# Archive-service HTTP client
# ---------------------------------------------------------------------------


class ArchiveClient:
    def __init__(self, base_url: str, token: str):
        self.base = base_url.rstrip("/")
        self.token = token

    def _req(self, method: str, path: str, *, data: bytes | None = None,
             json_body: dict | None = None,
             quiet_statuses: tuple[int, ...] = ()) -> bytes:
        url = self.base + path
        headers = {"Authorization": f"Bearer {self.token}"}
        body = data
        if json_body is not None:
            body = json.dumps(json_body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = Request(url, data=body, headers=headers, method=method)
        try:
            with urlopen(req) as r:
                return r.read()
        except HTTPError as e:
            # Some statuses are expected sentinels the caller handles
            # (e.g. 404 = "no unsorted episodes"); don't cry wolf for those.
            if e.code not in quiet_statuses:
                sys.stderr.write(
                    f"HTTP {e.code} on {method} {path}: {e.read().decode(errors='replace')[:500]}\n"
                )
            raise

    def list_episodes(self, *, limit: int, offset: int,
                      album: str | None = None) -> list[dict]:
        q = f"?limit={limit}&offset={offset}"
        if album:
            q += f"&album={album}"
        return json.loads(self._req("GET", "/episodes" + q))

    def list_all_episodes(self, *, page_size: int = 200,
                          album: str | None = None,
                          start_offset: int = 0) -> Iterable[dict]:
        offset = start_offset
        while True:
            page = self.list_episodes(limit=page_size, offset=offset, album=album)
            if not page:
                return
            for ep in page:
                yield ep
            if len(page) < page_size:
                return
            offset += page_size

    def list_unsorted_episodes(self) -> list[dict]:
        """/albums/Unsorted/episodes returns the imported-without-album
        rows (album IS NULL OR album=''). Different endpoint from
        /episodes?album= because the filter semantics differ for NULL.

        The server 404s ("no episodes for that album") when nothing is
        unsorted — a clean, healthy state, not an error. Treat it as an
        empty list so `validate --unsorted` no-ops instead of crashing."""
        try:
            return json.loads(
                self._req("GET", "/albums/Unsorted/episodes", quiet_statuses=(404,))
            )
        except HTTPError as e:
            if e.code == 404:
                return []
            raise

    def download_audio(self, episode_id: int, dest: Path) -> None:
        url = f"{self.base}/episodes/{episode_id}/audio"
        req = Request(url, headers={"Authorization": f"Bearer {self.token}"})
        with urlopen(req) as r, dest.open("wb") as f:
            while chunk := r.read(1 << 20):
                f.write(chunk)

    def patch_title(self, episode_id: int, title: str,
                    album: str | None = None) -> dict:
        body: dict = {"title": title}
        if album is not None:
            body["album"] = album
        return json.loads(self._req(
            "PATCH", f"/episodes/{episode_id}", json_body=body))

    def mark_validated(self, episode_id: int,
                       version: str | None = None) -> None:
        """Stamp title_validated_at on the row, recording which matcher
        did the checking. Best-effort: a 404 on an old server (no
        endpoint yet) is logged-and-swallowed so the run can finish."""
        try:
            self._req("PUT", f"/episodes/{episode_id}/title-validated",
                      json_body={"validator_version": version})
        except HTTPError as e:
            if e.code == 404:
                sys.stderr.write(
                    f"  (server has no title-validated endpoint; skipping stamp)\n"
                )
                return
            raise

    def delete_episode(self, episode_id: int) -> None:
        self._req("DELETE", f"/episodes/{episode_id}")

    def list_transcripts(self, episode_id: int) -> list[dict]:
        """Cached clip transcriptions for an episode. A server without
        the endpoint yet returns [] so the caller just transcribes."""
        try:
            return json.loads(self._req(
                "GET", f"/episodes/{episode_id}/transcripts",
                quiet_statuses=(404,)))
        except HTTPError as e:
            if e.code == 404:
                return []
            raise

    def put_transcript(self, episode_id: int, *, segment: str, secs: int,
                       model: str, text: str,
                       audio_sha256: str | None) -> None:
        """Cache a clip transcription. Best-effort — failing to WRITE
        the cache must never fail the run that produced the data."""
        try:
            self._req("PUT", f"/episodes/{episode_id}/transcripts",
                      json_body={"segment": segment, "secs": secs,
                                 "model": model, "text": text,
                                 "audio_sha256": audio_sha256},
                      quiet_statuses=(404,))
        except (HTTPError, URLError) as e:
            sys.stderr.write(f"    (transcript cache write failed: {e})\n")


# ---------------------------------------------------------------------------
# Whisperx transport (ffmpeg + ssh/pct + whisperx)
# ---------------------------------------------------------------------------


def _ffmpeg_tail(src: Path, dst: Path, secs: int) -> None:
    """Extract last `secs` of audio without re-encoding. -sseof is a
    negative offset from EOF that lets us skip CBR/VBR bitrate math.
    Used for AIO episodes (title announced at end)."""
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-sseof", f"-{secs}",
        "-i", str(src),
        "-t", str(secs),
        "-c", "copy",
        str(dst),
    ]
    subprocess.run(cmd, check=True, capture_output=True)


def _ffmpeg_head(src: Path, dst: Path, secs: int) -> None:
    """Extract first `secs` of audio. Used for YSH episodes — the YSH
    intro convention is 'I call my story, <title>' inside the first
    minute, so head extraction recovers the announced title."""
    cmd = [
        "ffmpeg", "-y", "-loglevel", "error",
        "-i", str(src),
        "-t", str(secs),
        "-c", "copy",
        str(dst),
    ]
    subprocess.run(cmd, check=True, capture_output=True)


def _ffprobe_ok(path: Path) -> bool:
    """Validate that a clip is parseable by ffprobe — guards the batch
    pipeline against malformed clips that would otherwise make
    whisperx abort the entire batch on startup.

    Real-world failure mode (seen 2026-06-08 on ep313-tail.mp3):
      [mp3 @ ...] Format mp3 detected only with low score of 1, misdetection possible!
      [mp3 @ ...] Invalid frame size (313): Could not seek to 795.
      Error opening input file ep313-tail.mp3.
      Error opening input files: Invalid argument

    `-c copy` ffmpeg extraction is happy to remux a borderline-corrupt
    source into a borderline-corrupt clip, but the downstream
    whisperx ffmpeg refuses it AND takes the whole batch down. So we
    ffprobe each clip after extraction; failures get excluded from
    the batch and reported as per-episode errors instead of nuking
    50 episodes of work.

    Returns True iff ffprobe reports a positive duration without
    erroring.
    """
    try:
        result = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "default=noprint_wrappers=1:nokey=1", str(path)],
            capture_output=True, text=True, timeout=10,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired):
        return False
    if result.returncode != 0:
        return False
    dur = result.stdout.strip()
    if not dur or dur in ("N/A", "0", "0.000000"):
        return False
    try:
        return float(dur) > 0
    except ValueError:
        return False


def _ssh(pve: str, cmd: str, *, capture: bool = True,
         input_bytes: bytes | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["ssh", "-o", "BatchMode=yes", pve, cmd],
        capture_output=capture,
        input=input_bytes,
    )


def _scp(local: Path, pve: str, remote: str) -> None:
    subprocess.run(
        ["scp", "-q", "-o", "BatchMode=yes", str(local), f"{pve}:{remote}"],
        check=True,
    )


@dataclass
class WhisperxConfig:
    pve: str = "root@192.168.2.123"
    ct: int = 112
    # whisperx isn't on $PATH for `pct exec`-launched non-login shells;
    # absolute path to the venv binary that podcast_reader's transcribe
    # pipeline also targets.
    bin: str = "/root/whisper-venv/bin/whisperx"
    model: str = "large-v3"
    device: str = "cuda"
    compute_type: str = "float16"


def transcribe_batch(
    clips: dict[str, Path],
    cfg: WhisperxConfig,
) -> dict[str, str]:
    """Transcribe many clips in ONE whisperx invocation.

    The whisperx large-v3 model takes ~30s to load and a few seconds
    to transcribe per clip. Calling whisperx per clip burns the load
    cost N times; batching N clips into one call amortizes it once.

    Pipeline:
      1. Stage clips into a flat tmpdir.
      2. tar.gz → scp to pve → pct push to CT 112 → tar -x.
      3. Single `whisperx file1.mp3 file2.mp3 ...` invocation.
      4. tar -c the JSON output dir → pct pull → tar -x locally.
      5. Parse each `<name>.json` and return {name: transcript_text}.

    Returns dict from clip name (the key in `clips`) to joined
    transcript text. Empty string for clips whisperx skipped or
    returned no segments for.
    """
    if not clips:
        return {}
    pid = os.getpid()
    pve_in_tar = f"/tmp/whisper-batch-in-{pid}.tar.gz"
    pve_out_tar = f"/tmp/whisper-batch-out-{pid}.tar.gz"
    ct_in_tar = f"/tmp/whisper-batch-in-{pid}.tar.gz"
    ct_in_dir = f"/tmp/whisper-batch-in-{pid}"
    ct_out_dir = f"/tmp/whisper-batch-out-{pid}"
    ct_out_tar = f"/tmp/whisper-batch-out-{pid}.tar.gz"

    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        # 1. Stage clips under a flat dir; tar it up.
        stage = td / "batch"
        stage.mkdir()
        for name, src in clips.items():
            (stage / f"{name}.mp3").write_bytes(src.read_bytes())
        local_tar = td / "batch.tar.gz"
        subprocess.run(
            ["tar", "-C", str(td), "-czf", str(local_tar), "batch"],
            check=True, capture_output=True,
        )
        # 2. Ship to pve, then into the LXC, then untar.
        _scp(local_tar, cfg.pve, pve_in_tar)
        push = _ssh(
            cfg.pve,
            f"pct push {cfg.ct} {shlex.quote(pve_in_tar)} {shlex.quote(ct_in_tar)}"
        )
        if push.returncode != 0:
            raise RuntimeError(
                f"pct push failed: {push.stderr.decode(errors='replace')[-500:]}"
            )
        untar = _ssh(
            cfg.pve,
            f"pct exec {cfg.ct} -- bash -c "
            + shlex.quote(
                f"rm -rf {ct_in_dir} && mkdir -p {ct_in_dir} && "
                f"tar -C {ct_in_dir} --strip-components=1 -xzf {ct_in_tar}"
            ),
        )
        if untar.returncode != 0:
            raise RuntimeError(
                f"tar -x in CT failed: {untar.stderr.decode(errors='replace')[-500:]}"
            )

        # 3. Run whisperx on all clips in the input dir, one process.
        # Globbing with `*.mp3` keeps the command short even for big batches.
        wx_cmd = (
            f"rm -rf {ct_out_dir} && mkdir -p {ct_out_dir} && cd {ct_in_dir} && "
            f"{shlex.quote(cfg.bin)} *.mp3 "
            f"--model {cfg.model} --device {cfg.device} "
            f"--compute_type {cfg.compute_type} "
            f"--output_format json --output_dir {ct_out_dir}"
        )
        wx = _ssh(
            cfg.pve,
            f"pct exec {cfg.ct} -- bash -c {shlex.quote(wx_cmd)}",
        )
        if wx.returncode != 0:
            raise RuntimeError(
                f"whisperx batch failed (rc={wx.returncode}): "
                f"{wx.stderr.decode(errors='replace')[-1500:]}"
            )

        # 4. tar.gz the output dir, pull back to the dev machine, untar.
        tar_out = _ssh(
            cfg.pve,
            f"pct exec {cfg.ct} -- bash -c "
            + shlex.quote(f"tar -C {ct_out_dir} -czf {ct_out_tar} .")
        )
        if tar_out.returncode != 0:
            raise RuntimeError(
                f"tar output failed: {tar_out.stderr.decode(errors='replace')[-500:]}"
            )
        pull = _ssh(
            cfg.pve,
            f"pct pull {cfg.ct} {ct_out_tar} {pve_out_tar}"
        )
        if pull.returncode != 0:
            raise RuntimeError(
                f"pct pull failed: {pull.stderr.decode(errors='replace')[-500:]}"
            )
        local_out_tar = td / "out.tar.gz"
        subprocess.run(
            ["scp", "-q", "-o", "BatchMode=yes",
             f"{cfg.pve}:{pve_out_tar}", str(local_out_tar)],
            check=True,
        )
        out_dir = td / "out"
        out_dir.mkdir()
        subprocess.run(
            ["tar", "-C", str(out_dir), "-xzf", str(local_out_tar)],
            check=True, capture_output=True,
        )

        # 5. Parse JSONs.
        results: dict[str, str] = {}
        for name in clips:
            jp = out_dir / f"{name}.json"
            if not jp.exists():
                results[name] = ""
                continue
            try:
                data = json.loads(jp.read_text())
            except json.JSONDecodeError:
                results[name] = ""
                continue
            text = " ".join(
                (s.get("text") or "").strip()
                for s in data.get("segments", [])
            ).strip()
            results[name] = text

        # 6. Best-effort remote cleanup.
        _ssh(
            cfg.pve,
            f"rm -f {shlex.quote(pve_in_tar)} {shlex.quote(pve_out_tar)}; "
            f"pct exec {cfg.ct} -- bash -c "
            + shlex.quote(
                f"rm -rf {ct_in_tar} {ct_out_tar} {ct_in_dir} {ct_out_dir}"
            ),
        )
        return results


def transcribe_clip(
    audio: Path,
    cfg: WhisperxConfig,
    secs: int,
    *,
    mode: str = "tail",
) -> str:
    """End-to-end: ffmpeg-clip (tail or head depending on `mode`) →
    scp → pct push → whisperx → pct pull → parse → cleanup. Returns
    the joined transcript text (no timestamps). Raises on whisperx
    failure so the caller can mark the row as unverified rather than
    silently dropping it.
    """
    if mode not in ("tail", "head"):
        raise ValueError(f"mode must be 'tail' or 'head', got {mode!r}")
    stem = audio.stem
    with tempfile.TemporaryDirectory() as td:
        clip = Path(td) / f"{stem}-{mode}.mp3"
        (_ffmpeg_tail if mode == "tail" else _ffmpeg_head)(audio, clip, secs)
        tail = clip  # keep variable name below stable to minimize diff

        pve_tmp = f"/tmp/whisper-titles-{stem}.mp3"
        ct_tmp = f"/tmp/whisper-titles-{stem}.mp3"
        ct_out = f"/tmp/whisper-titles-out-{stem}"

        _scp(tail, cfg.pve, pve_tmp)
        push = _ssh(cfg.pve, f"pct push {cfg.ct} {shlex.quote(pve_tmp)} {shlex.quote(ct_tmp)}")
        if push.returncode != 0:
            raise RuntimeError(
                f"pct push failed: {push.stderr.decode(errors='replace')[-500:]}"
            )

        wx = (
            f"pct exec {cfg.ct} -- bash -c "
            + shlex.quote(
                f"mkdir -p {ct_out} && "
                f"{shlex.quote(cfg.bin)} {shlex.quote(ct_tmp)} "
                f"--model {cfg.model} --device {cfg.device} "
                f"--compute_type {cfg.compute_type} "
                f"--output_format json --output_dir {ct_out}"
            )
        )
        run = _ssh(cfg.pve, wx)
        if run.returncode != 0:
            raise RuntimeError(
                f"whisperx failed rc={run.returncode}: "
                f"{run.stderr.decode(errors='replace')[-1000:]}"
            )

        out_json = f"{ct_out}/{Path(ct_tmp).stem}.json"
        pull = _ssh(cfg.pve, f"pct pull {cfg.ct} {shlex.quote(out_json)} /dev/stdout")
        if pull.returncode != 0 or not pull.stdout.strip():
            raise RuntimeError(
                f"pct pull failed rc={pull.returncode}: "
                f"{pull.stderr.decode(errors='replace')[-500:]}"
            )
        data = json.loads(pull.stdout)

        # Cleanup is best-effort — leaks a few MB of /tmp on the ssh
        # box and CT 112 if it fails; doesn't affect correctness.
        _ssh(
            cfg.pve,
            f"rm -f {shlex.quote(pve_tmp)}; "
            f"pct exec {cfg.ct} -- rm -rf {shlex.quote(ct_tmp)} {shlex.quote(ct_out)}",
        )

    text = " ".join((seg.get("text") or "").strip() for seg in data.get("segments", []))
    return text.strip()


# ---------------------------------------------------------------------------
# Subcommands
# ---------------------------------------------------------------------------


@dataclass
class ReportEntry:
    episode_id: int
    current_title: str
    current_album: str | None
    transcript: str
    best_title: str | None
    best_album: str | None
    confidence: float
    error: str | None = None


def cmd_validate(args: argparse.Namespace) -> int:
    client = ArchiveClient(args.base_url, args.token)
    catalog = load_catalog(args.catalog)
    cfg = WhisperxConfig(
        pve=args.pve,
        ct=args.whisperx_ct,
        bin=args.whisperx_bin,
        model=args.model,
        device=args.device,
        compute_type=args.compute_type,
    )
    out: list[ReportEntry] = []
    total_listed = 0
    if args.unsorted:
        # /albums/Unsorted/episodes returns all rows with NULL album in
        # one shot — no pagination, so honor --offset manually.
        ep_list = client.list_unsorted_episodes()[args.offset:]
    else:
        ep_list = list(client.list_all_episodes(
            album=args.album, start_offset=args.offset))
    # Provider-specific candidate sets:
    #   AIO  → fuzzy-match against the shipped catalog (rich set, has
    #          album metadata).
    #   YSH  → fuzzy-match against the set of YSH titles already in
    #          the archive itself. No YSH catalog ships with the
    #          server; this is enough to detect mis-labeled rows
    #          (the audio for sku-447 should announce the title
    #          stored in some YSH row).
    ysh_candidates = _ysh_candidates_from_archive(client) if any(
        ep.get("provider_id") == "ysh" for ep in ep_list
    ) else []

    # Plan up front: which episodes to process, which segments to clip
    # for each. Skipping already-validated rows here so the batches
    # don't waste GPU on confirmed ones.
    queue: list[dict] = []
    skipped_already_validated = 0
    for ep in ep_list:
        if args.limit and len(queue) >= args.limit:
            break
        # Skip only rows the CURRENT matcher has already seen. A row
        # stamped by an older matcher (or before versioning existed)
        # still needs re-checking — that's the whole point of the
        # version column.
        if not needs_recheck(ep) and not args.revalidate:
            skipped_already_validated += 1
            continue
        queue.append(ep)
    if skipped_already_validated:
        sys.stderr.write(
            f"[validate] skipping {skipped_already_validated} row(s) already "
            f"checked by the current matcher; pass --revalidate to force\n"
        )

    # Process in batches so one whisperx model load amortizes across
    # all clips in the batch. AIO episodes contribute one clip (tail);
    # YSH episodes contribute two (head + tail) so the per-episode
    # if-head-missed fallback collapses into a single batch round-trip.
    sys.stderr.write(
        f"[validate] {len(queue)} episode(s) to process "
        f"in batches of {args.batch_size}\n"
    )

    for batch_idx, batch in enumerate(_chunked(queue, args.batch_size), start=1):
        sys.stderr.write(
            f"[batch {batch_idx:>3}/{(len(queue) + args.batch_size - 1) // args.batch_size}] "
            f"{len(batch)} episode(s) "
        )
        sys.stderr.flush()
        out.extend(_process_batch(
            batch, client, cfg, catalog, ysh_candidates,
            tail_secs=args.tail_secs, head_secs=args.head_secs,
        ))
    args.out.write_text(json.dumps([asdict(e) for e in out], indent=2))
    sys.stderr.write(f"[validate] wrote {len(out)} entries → {args.out}\n")
    return 0


def _chunked(seq, size):
    """Yield successive chunks of `seq` with at most `size` items."""
    it = iter(seq)
    while True:
        chunk = list(itertools.islice(it, size))
        if not chunk:
            return
        yield chunk


def _process_batch(
    batch: list[dict],
    client: "ArchiveClient",
    cfg: WhisperxConfig,
    catalog: list[CatalogEpisode],
    ysh_candidates: list[str],
    *,
    tail_secs: int,
    head_secs: int,
) -> list[ReportEntry]:
    """One whisperx batch: download → clip → tar → ship → transcribe →
    score → return entries. Each entry stamps title_validated_at on
    the server best-effort."""
    rows: list[ReportEntry] = []
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        # 1. Download audios + ffmpeg-clip per planned segment.
        # clip_name → (Path on local disk, ep_dict, segment_type).
        clips: dict[str, Path] = {}
        plans: dict[str, tuple[dict, str]] = {}
        download_errors: list[ReportEntry] = []
        for ep in batch:
            eid = ep["episode_id"]
            provider = (ep.get("provider_id") or "aio").lower()
            full = td / f"{eid}.mp3"
            try:
                client.download_audio(eid, full)
            except Exception as exc:
                download_errors.append(ReportEntry(
                    eid, ep["title"], ep.get("album"), "",
                    None, None, 0.0, f"download: {exc}",
                ))
                continue
            try:
                if provider == "ysh":
                    # Both head + tail every time; the batch makes
                    # the extra clip ~free vs. the model-load cost.
                    h = td / f"ep{eid}-head.mp3"
                    t = td / f"ep{eid}-tail.mp3"
                    _ffmpeg_head(full, h, head_secs)
                    _ffmpeg_tail(full, t, tail_secs)
                    pairs = [
                        (f"ep{eid}-head", h, "head"),
                        (f"ep{eid}-tail", t, "tail"),
                    ]
                else:
                    t = td / f"ep{eid}-tail.mp3"
                    _ffmpeg_tail(full, t, tail_secs)
                    pairs = [(f"ep{eid}-tail", t, "tail")]
            except subprocess.CalledProcessError as exc:
                download_errors.append(ReportEntry(
                    eid, ep["title"], ep.get("album"), "",
                    None, None, 0.0, f"ffmpeg: {exc}",
                ))
                continue

            # ffprobe each extracted clip before batching it. A
            # malformed clip would make whisperx abort the whole
            # batch on startup ("Error opening input files: Invalid
            # argument") — losing N-1 episodes of correct work. Bad
            # clips here become per-episode errors and the rest of
            # the batch proceeds normally.
            bad = [name for name, p, _ in pairs if not _ffprobe_ok(p)]
            if bad:
                download_errors.append(ReportEntry(
                    eid, ep["title"], ep.get("album"), "",
                    None, None, 0.0,
                    f"ffprobe rejected clip(s): {bad}",
                ))
                continue
            for name, p, segment in pairs:
                clips[name] = p
                plans[name] = (ep, segment)

        if not clips:
            sys.stderr.write("  (all downloads failed)\n")
            return download_errors

        # 2. One whisperx call for the whole batch.
        try:
            sys.stderr.write(f"({len(clips)} clip(s)) … ")
            sys.stderr.flush()
            transcripts = transcribe_batch(clips, cfg)
        except Exception as exc:
            sys.stderr.write(f"BATCH ERR: {exc}\n")
            for ep in batch:
                rows.append(ReportEntry(
                    ep["episode_id"], ep["title"], ep.get("album"), "",
                    None, None, 0.0, f"whisperx batch: {exc}",
                ))
            return rows + download_errors

        # 3. Score per episode. For YSH, score head + tail and keep
        # the higher confidence (with its corresponding transcript).
        by_ep: dict[int, dict] = {}
        for clip_name, transcript in transcripts.items():
            ep, segment = plans[clip_name]
            eid = ep["episode_id"]
            provider = (ep.get("provider_id") or "aio").lower()
            if provider == "ysh":
                title, score = best_ysh_match(transcript, ysh_candidates)
                album = None
            else:
                entry, score = best_catalog_match(transcript, catalog)
                title = entry.title if entry else None
                album = entry.album if entry else None
            prev = by_ep.get(eid)
            cand = {
                "transcript": transcript,
                "best_title": title,
                "best_album": album,
                "confidence": score,
                "segment": segment,
            }
            if prev is None or cand["confidence"] > prev["confidence"]:
                by_ep[eid] = cand

        # 4. Emit ReportEntry per episode, log winner.
        for ep in batch:
            eid = ep["episode_id"]
            if eid not in by_ep:
                # Download error — already in download_errors.
                continue
            r = by_ep[eid]
            sys.stderr.write(
                f"  {(ep.get('provider_id') or 'aio'):>3}/{eid:>7} "
                f"\"{ep['title']}\" "
                f"({r['segment']}) -> "
                f"\"{r['best_title'] or '(none)'}\" "
                f"({r['confidence']:.2f})\n"
            )
            rows.append(ReportEntry(
                episode_id=eid,
                current_title=ep["title"],
                current_album=ep.get("album"),
                transcript=r["transcript"],
                best_title=r["best_title"],
                best_album=r["best_album"],
                confidence=r["confidence"],
            ))
            # Best-effort validation stamp on the server.
            try:
                client.mark_validated(
                    eid, matcher_version(ep.get("provider_id")))
            except Exception as exc:
                sys.stderr.write(f"    (mark_validated: {exc})\n")
    return rows + download_errors


def _is_same_base_part(current: str, candidate: str) -> bool:
    """True when `current` and `candidate` differ only by their
    "Part X of N" suffix. Audio can confirm the base title but not
    which part of a multi-parter is playing, so when the matcher
    suggests swapping Part 3 → Part 1 we treat that as already-correct
    rather than a mis-title."""
    a = _strip_part_suffix(current).strip().lower()
    b = _strip_part_suffix(candidate).strip().lower()
    return bool(a) and a == b and current != candidate


def cmd_plan(args: argparse.Namespace) -> int:
    """Preview what `apply` would do. No server contact; reads the
    validate-report JSON and bucketizes every entry."""
    report = json.loads(args.report.read_text())
    scanned = len(report)
    errored = sum(1 for e in report if e.get("error"))
    proposed: list[dict] = []
    already_correct = 0
    below = 0
    no_match = 0
    # Confidence histogram buckets for the entries with a match.
    buckets = [
        ("1.00",     lambda x: x == 1.0),
        ("0.90-0.99", lambda x: 0.9 <= x < 1.0),
        ("0.85-0.89", lambda x: 0.85 <= x < 0.9),
        ("0.70-0.84", lambda x: 0.7 <= x < 0.85),
        ("<0.70",    lambda x: x < 0.7),
    ]
    bucket_counts = [0] * len(buckets)
    for e in report:
        if e.get("error"):
            continue
        if not e.get("best_title"):
            no_match += 1
            continue
        conf = float(e.get("confidence", 0.0))
        for i, (_, pred) in enumerate(buckets):
            if pred(conf):
                bucket_counts[i] += 1
                break
        if conf < args.threshold:
            below += 1
            continue
        if e["best_title"] == e["current_title"]:
            already_correct += 1
            continue
        # Same base title, different "Part X of N" — audio can confirm
        # the show but not which part. Treat as correct, not as a
        # rename candidate.
        if _is_same_base_part(e["current_title"], e["best_title"]):
            already_correct += 1
            continue
        proposed.append(e)

    proposed.sort(key=lambda e: e["confidence"], reverse=True)

    print(f"scanned                  {scanned}")
    print(f"transcription errored    {errored}")
    print(f"no catalog match         {no_match}")
    print(f"already correct          {already_correct}")
    print(f"below threshold {args.threshold:.2f}    {below}")
    print(f"PROPOSED CHANGES         {len(proposed)}")
    print()
    print("confidence histogram (entries with a candidate match):")
    for (label, _), count in zip(buckets, bucket_counts):
        bar = "#" * min(count, 40)
        print(f"  {label:<11} {count:>4}  {bar}")
    print()

    if not proposed:
        print("(no proposed changes above threshold)")
        return 0

    show = proposed if args.top == 0 else proposed[: args.top]
    print(f"proposed changes (showing {len(show)} of {len(proposed)}):")
    for e in show:
        cur = e["current_title"]
        new = e["best_title"]
        album = e.get("best_album") or e.get("current_album") or ""
        print(
            f"  {e['episode_id']:>7}  conf={e['confidence']:.2f}  "
            f"\"{cur}\"  ->  \"{new}\""
            + (f"  [{album}]" if album else "")
        )
    return 0


def cmd_apply(args: argparse.Namespace) -> int:
    client = ArchiveClient(args.base_url, args.token)
    report = json.loads(args.report.read_text())
    changed = skipped_low = skipped_match = skipped_err = 0
    for entry in report:
        eid = entry["episode_id"]
        if entry.get("error"):
            skipped_err += 1
            continue
        if entry["confidence"] < args.threshold:
            skipped_low += 1
            continue
        new_title = entry.get("best_title")
        if not new_title or new_title == entry["current_title"]:
            skipped_match += 1
            continue
        if _is_same_base_part(entry["current_title"], new_title):
            skipped_match += 1
            continue
        new_album = entry.get("best_album") if args.fix_album else None
        sys.stderr.write(
            f"[apply] {eid:>7} \"{entry['current_title']}\" -> "
            f"\"{new_title}\" (conf={entry['confidence']:.2f})"
            + (f" album={new_album}" if new_album else "")
            + "\n"
        )
        if not args.dry_run:
            client.patch_title(eid, new_title, album=new_album)
        changed += 1
    sys.stderr.write(
        f"[apply] {'would-change' if args.dry_run else 'changed'}={changed} "
        f"skipped_low_conf={skipped_low} skipped_already_match={skipped_match} "
        f"skipped_err={skipped_err}\n"
    )
    return 0


def cmd_dedup(args: argparse.Namespace) -> int:
    client = ArchiveClient(args.base_url, args.token)
    groups: dict[tuple[str, str | None], list[dict]] = {}
    for ep in client.list_all_episodes(album=args.album):
        key = (_norm(ep["title"]), ep.get("album"))
        groups.setdefault(key, []).append(ep)
    duplicates = {k: v for k, v in groups.items() if len(v) > 1}
    sys.stderr.write(f"[dedup] {len(duplicates)} duplicate group(s)\n")
    deleted = 0
    for (title_n, album), eps in duplicates.items():
        # Sort by file_size desc — keep the biggest copy (usually the
        # fullest, least-truncated). Tie-break on archived_at desc.
        eps.sort(key=lambda r: (r["file_size"], r["archived_at"]), reverse=True)
        keep, *losers = eps
        sys.stderr.write(
            f"  group \"{eps[0]['title']}\" album={album} "
            f"keep={keep['episode_id']} ({keep['file_size']} bytes) "
            f"losers={[e['episode_id'] for e in losers]}\n"
        )
        if args.delete_smaller:
            for ep in losers:
                if not args.dry_run:
                    client.delete_episode(ep["episode_id"])
                deleted += 1
    if args.delete_smaller:
        sys.stderr.write(
            f"[dedup] {'would-delete' if args.dry_run else 'deleted'}={deleted}\n"
        )
    return 0


# ---------------------------------------------------------------------------
# audit-ysh — full-sweep provenance + title check over the YSH library
# ---------------------------------------------------------------------------


@dataclass
class YshAuditEntry:
    episode_id: int
    external_id: str | None
    title: str
    album: str | None
    sku_id: int | None
    # Metadata layer (no audio needed)
    catalog_title: str | None
    catalog_album: str | None
    metadata_title_ok: bool | None      # None = sku not in catalog
    metadata_album_ok: bool | None
    # Audio layer
    provenance: str = "skipped"         # ysh|foreign|ambiguous|unknown|skipped
    ysh_score: float = 0.0
    foreign_score: float = 0.0
    ysh_hits: list[str] | None = None
    foreign_hits: list[str] | None = None
    title_verdict: str = "skipped"      # match|mismatch|inconclusive|skipped
    own_score: float = 0.0
    best_title: str | None = None
    best_score: float = 0.0
    best_album: str | None = None
    anchored: bool = False              # best_title followed a credit phrase
    segment: str | None = None          # which clip won ("head"/"tail")
    transcript: str = ""                # the winning clip's text
    # Both clips are kept verbatim so `--from-report --rescore` can
    # re-run the matcher against new thresholds without spending
    # another hour of GPU re-transcribing audio that didn't change.
    head_transcript: str = ""
    tail_transcript: str = ""
    audio_sha256: str | None = None     # cache key: invalidates on re-ingest
    from_cache: bool = False            # transcripts came from the DB cache
    error: str | None = None

    @property
    def clean(self) -> bool:
        """True when nothing about this row needs a human. "skipped"
        counts as clean so a --no-audio run reports on the metadata
        checks alone instead of flagging all 101 rows."""
        return (
            self.error is None
            and self.provenance in ("ysh", "skipped")
            and self.title_verdict in ("match", "skipped")
            and self.metadata_title_ok is not False
            and self.metadata_album_ok is not False
        )


def _ysh_metadata_check(
    ep: dict,
    by_sku: dict[int, YshTrack],
) -> YshAuditEntry:
    """Build the audio-free half of an audit entry: does the row's
    stored title/album agree with the yourstoryhour.org catalog entry
    for its SKU? Free (no GPU, no download) and catches the class of
    mislabeling that never reaches the audio — a right file with a
    wrong album, or a SKU that isn't in the catalog at all."""
    sku = sku_id_from_external_id(ep.get("external_id"))
    track = by_sku.get(sku) if sku is not None else None
    title = (ep.get("title") or "").strip()
    album = (ep.get("album") or "").strip() or None
    if track is None:
        cat_title = cat_album = None
        t_ok = a_ok = None
    else:
        cat_title, cat_album = track.title, track.album or None
        t_ok = _norm(title) == _norm(cat_title)
        # An empty album on the row is a gap, not a conflict, but it
        # still wants reporting — treat it as a mismatch so the album
        # backfill gets a nudge.
        a_ok = bool(cat_album) and bool(album) and _norm(album) == _norm(cat_album)
        if not cat_album:
            a_ok = None
    return YshAuditEntry(
        episode_id=ep["episode_id"],
        audio_sha256=ep.get("sha256"),
        external_id=ep.get("external_id"),
        title=title,
        album=album,
        sku_id=sku,
        catalog_title=cat_title,
        catalog_album=cat_album,
        metadata_title_ok=t_ok,
        metadata_album_ok=a_ok,
    )


def _score_and_log(
    batch: list["YshAuditEntry"],
    candidates: list[str],
    album_of: dict[str, str],
    *,
    threshold: float,
    per_ep: dict[int, dict[str, str]],
) -> None:
    """Score every entry in the batch and print its one-line verdict.

    Entries served from the transcript cache already carry their text;
    freshly transcribed ones take it from `per_ep`. Shared so a cache
    hit and a GPU pass produce byte-identical verdicts.
    """
    for entry in batch:
        if entry.error:
            continue
        if not entry.from_cache:
            segs = per_ep.get(entry.episode_id)
            if not segs:
                entry.error = "whisperx returned no transcript"
                continue
            entry.head_transcript = segs.get("head", "")
            entry.tail_transcript = segs.get("tail", "")
        score_entry(entry, candidates, album_of, threshold=threshold)

        flag = "ok " if entry.clean else "!! "
        src = " (cached)" if entry.from_cache else ""
        sys.stderr.write(
            f"  {flag}{entry.episode_id:>7} \"{entry.title}\" "
            f"prov={entry.provenance}"
            f"(y{entry.ysh_score:.1f}/f{entry.foreign_score:.1f}) "
            f"title={entry.title_verdict}"
            f"(own {entry.own_score:.2f}"
            + (f", best \"{entry.best_title}\" {entry.best_score:.2f}"
               if entry.title_verdict == "mismatch" else "")
            + f"){src}\n"
        )


def _cached_clips(
    client: "ArchiveClient",
    entry: "YshAuditEntry",
    model: str,
    *,
    head_secs: int,
    tail_secs: int,
) -> tuple[str, str] | None:
    """Return (head_text, tail_text) when BOTH clips are already cached
    server-side for this exact (episode, segment, secs, model, sha), or
    None when anything is missing.

    All-or-nothing on purpose: a partial hit still needs the audio
    downloaded and clipped, at which point transcribing the second clip
    alongside the first is nearly free — the model load dominates.

    The match is exact rather than "cached clip is at least as long as
    requested". A longer clip's transcript would arguably answer a
    shorter clip's question, but not the reverse, and the asymmetry is
    an easy thing to get backwards later; an exact key can only ever
    produce an honest miss.
    """
    try:
        rows = client.list_transcripts(entry.episode_id)
    except Exception as exc:
        sys.stderr.write(f"    (transcript cache read failed: {exc})\n")
        return None
    sha = entry.audio_sha256 or ""
    want = {"head": head_secs, "tail": tail_secs}
    found: dict[str, str] = {}
    for r in rows:
        seg = r.get("segment")
        if (seg in want
                and r.get("secs") == want[seg]
                and r.get("model") == model
                and (r.get("audio_sha256") or "") == sha):
            found[seg] = r.get("text") or ""
    if len(found) == 2:
        return found["head"], found["tail"]
    return None


def _audit_batch(
    batch: list[YshAuditEntry],
    client: "ArchiveClient",
    cfg: WhisperxConfig,
    candidates: list[str],
    album_of: dict[str, str],
    *,
    head_secs: int,
    tail_secs: int,
    threshold: float,
) -> None:
    """Transcribe head+tail for each entry in `batch` and fill in the
    audio half of the entry IN PLACE.

    Head and tail are both transcribed because they answer different
    questions: the credit line ("I call my story, X") lives in the
    head, but the station ID ("...has been Your Story Hour, from
    Berrien Springs") reliably lives in the tail. Provenance therefore
    scores the CONCATENATION of both clips, while the title verdict
    takes the better-scoring of the two.
    """
    with tempfile.TemporaryDirectory() as td:
        td = Path(td)
        clips: dict[str, Path] = {}
        plans: dict[str, tuple[YshAuditEntry, str]] = {}

        # Phase 0: anything the transcript cache already holds skips the
        # download, the clipping and the GPU entirely. Transcribing is
        # ~95% of this pipeline's cost and a transcript of unchanged
        # audio stays valid forever, so this is the difference between
        # a matcher change costing a GPU hour and costing seconds.
        pending: list[YshAuditEntry] = []
        for entry in batch:
            cached = _cached_clips(client, entry, cfg.model,
                                   head_secs=head_secs, tail_secs=tail_secs)
            if cached is None:
                pending.append(entry)
                continue
            entry.head_transcript, entry.tail_transcript = cached
            entry.from_cache = True
        if len(pending) < len(batch):
            sys.stderr.write(
                f"[{len(batch) - len(pending)} cached] ")
            sys.stderr.flush()

        for entry in pending:
            eid = entry.episode_id
            full = td / f"{eid}.mp3"
            try:
                client.download_audio(eid, full)
            except Exception as exc:
                entry.error = f"download: {exc}"
                continue
            try:
                h = td / f"ep{eid}-head.mp3"
                t = td / f"ep{eid}-tail.mp3"
                _ffmpeg_head(full, h, head_secs)
                _ffmpeg_tail(full, t, tail_secs)
            except subprocess.CalledProcessError as exc:
                entry.error = f"ffmpeg: {exc}"
                continue
            pairs = [(f"ep{eid}-head", h, "head"), (f"ep{eid}-tail", t, "tail")]
            # Same batch-poisoning guard validate() uses: one malformed
            # clip makes whisperx abort the whole invocation.
            bad = [n for n, p, _ in pairs if not _ffprobe_ok(p)]
            if bad:
                entry.error = f"ffprobe rejected clip(s): {bad}"
                continue
            for name, p, segment in pairs:
                clips[name] = p
                plans[name] = (entry, segment)

        if not clips:
            if any(e.from_cache for e in batch):
                sys.stderr.write("(all cached) ")
            else:
                sys.stderr.write("  (no usable clips in batch)\n")
            _score_and_log(batch, candidates, album_of, threshold=threshold,
                           per_ep={})
            return

        try:
            sys.stderr.write(f"({len(clips)} clip(s)) … ")
            sys.stderr.flush()
            transcripts = transcribe_batch(clips, cfg)
        except Exception as exc:
            sys.stderr.write(f"BATCH ERR: {exc}\n")
            for entry in pending:
                if entry.error is None:
                    entry.error = f"whisperx batch: {exc}"
            _score_and_log(batch, candidates, album_of, threshold=threshold,
                           per_ep={})
            return

        # Regroup clip transcripts per episode, and cache each one so
        # the next run (or the next matcher) never pays for it again.
        per_ep: dict[int, dict[str, str]] = {}
        for clip_name, text in transcripts.items():
            entry, segment = plans[clip_name]
            per_ep.setdefault(entry.episode_id, {})[segment] = text
            client.put_transcript(
                entry.episode_id, segment=segment,
                secs=head_secs if segment == "head" else tail_secs,
                model=cfg.model, text=text,
                audio_sha256=entry.audio_sha256,
            )

        _score_and_log(batch, candidates, album_of, threshold=threshold,
                       per_ep=per_ep)


def score_entry(
    entry: "YshAuditEntry",
    candidates: list[str],
    album_of: dict[str, str],
    *,
    threshold: float,
) -> None:
    """Fill in an entry's provenance + title verdict from the two clip
    transcripts already stored on it. Mutates `entry` in place.

    Split out from the batch loop so `--from-report --rescore` runs the
    exact same scoring the live pass does — a re-scored report and a
    fresh run can't drift apart.
    """
    head = entry.head_transcript
    tail = entry.tail_transcript

    # Provenance reads both clips together: the credit line lives in
    # the head but the station ID that names the show is in the tail.
    prov = ysh_provenance(head + " \n " + tail)
    entry.provenance = prov.verdict
    entry.ysh_score = prov.ysh_score
    entry.foreign_score = prov.foreign_score
    entry.ysh_hits = prov.ysh_hits
    entry.foreign_hits = prov.foreign_hits

    # Title: score each clip independently, keep the stronger verdict.
    # The tail is teaser-trimmed first — its closing plug for next
    # week's story would otherwise be read as this episode's title.
    best_tv: TitleVerdict | None = None
    best_seg = None
    for segment, text in (("head", head), ("tail", trim_teaser(tail))):
        if not text:
            continue
        tv = ysh_title_verdict(
            text, entry.title, candidates,
            threshold=threshold, album_of=album_of,
        )
        if best_tv is None or _verdict_rank(tv) > _verdict_rank(best_tv):
            best_tv, best_seg = tv, segment
    if best_tv is None:
        entry.error = entry.error or "empty transcript"
        return
    entry.title_verdict = best_tv.verdict
    entry.own_score = best_tv.own_score
    entry.best_title = best_tv.best_title
    entry.best_score = best_tv.best_score
    entry.best_album = best_tv.best_album
    entry.anchored = best_tv.anchored
    entry.segment = best_seg
    entry.transcript = head if best_seg == "head" else tail


def _verdict_rank(tv: "TitleVerdict") -> tuple[int, float, float]:
    """Ordering key for picking which clip's verdict to report.

    Verdict class dominates the scores. A confirmation from EITHER clip
    settles the row, so "match" outranks everything. Next comes
    "mismatch": without this rule a genuine swap spotted in the
    head could be buried by a tail whose own-title score happened to
    land a hundredth higher — an accusation is worth surfacing over an
    "I don't know". Scores only break ties inside a class.
    """
    order = {"match": 2, "mismatch": 1, "inconclusive": 0}
    return (order.get(tv.verdict, 0), tv.own_score, tv.best_score)


def _print_ysh_audit_summary(entries: list[YshAuditEntry], threshold: float) -> int:
    """Human-readable rollup. Returns the number of rows needing review."""
    total = len(entries)
    w = sys.stdout.write

    def bucket(pred):
        return [e for e in entries if pred(e)]

    errored = bucket(lambda e: e.error)
    foreign = bucket(lambda e: e.provenance == "foreign")
    ambiguous = bucket(lambda e: e.provenance == "ambiguous")
    unknown = bucket(lambda e: e.provenance == "unknown")
    mismatch = bucket(lambda e: e.title_verdict == "mismatch")
    inconclusive = bucket(lambda e: e.title_verdict == "inconclusive" and not e.error)
    meta_title = bucket(lambda e: e.metadata_title_ok is False)
    meta_album = bucket(lambda e: e.metadata_album_ok is False)
    no_sku = bucket(lambda e: e.sku_id is None)
    off_catalog = bucket(lambda e: e.sku_id is not None and e.catalog_title is None)
    clean = bucket(lambda e: e.clean)

    # A --no-audio run leaves every audio verdict at "skipped"; printing
    # four zeroed buckets would read as "we checked and found nothing"
    # when we never listened at all.
    audio_ran = any(e.provenance != "skipped" for e in entries)

    w(f"\nYSH audit — {total} episode(s), threshold {threshold:.2f}"
      f"{'' if audio_ran else '  [metadata only — no audio inspected]'}\n")
    w("=" * 72 + "\n")
    clean_label = ("clean (YSH + title confirmed + catalog agrees)"
                   if audio_ran else "clean (catalog agrees)")
    w(f"  {clean_label:<47}: {len(clean)}\n")
    w(f"  {'transcription/download errors':<47}: {len(errored)}\n")
    if audio_ran:
        w("\n  provenance — does the audio belong to YSH?\n")
        w(f"    confirmed YSH                                : "
          f"{len(bucket(lambda e: e.provenance == 'ysh'))}\n")
        w(f"    FOREIGN (another show's audio)               : {len(foreign)}\n")
        w(f"    ambiguous (both shows' branding)             : {len(ambiguous)}\n")
        w(f"    unknown (no station ID heard)                : {len(unknown)}\n")
        w("\n  title — does the announced story match the row?\n")
        w(f"    confirmed                                    : "
          f"{len(bucket(lambda e: e.title_verdict == 'match'))}\n")
        w(f"    MISMATCH (announced a different story)       : {len(mismatch)}\n")
        w(f"    inconclusive                                 : {len(inconclusive)}\n")
    w("\n  catalog metadata (no audio needed)\n")
    w(f"    title disagrees with yourstoryhour.org       : {len(meta_title)}\n")
    w(f"    album disagrees with yourstoryhour.org       : {len(meta_album)}\n")
    w(f"    row has no ysh-sku-* external_id             : {len(no_sku)}\n")
    w(f"    sku not present in the catalog               : {len(off_catalog)}\n")

    def listing(label: str, rows: list[YshAuditEntry], fmt) -> None:
        if not rows:
            return
        w(f"\n{label} ({len(rows)})\n" + "-" * 72 + "\n")
        for e in rows:
            w(fmt(e) + "\n")

    listing(
        "NOT YOUR STORY HOUR — remove or re-ingest these",
        foreign,
        lambda e: (f"  {e.episode_id:>7} \"{e.title}\" [{e.external_id}]\n"
                   f"          heard: {', '.join(e.foreign_hits or []) or '(none)'}"),
    )
    listing(
        "AMBIGUOUS PROVENANCE — listen to confirm",
        ambiguous,
        lambda e: (f"  {e.episode_id:>7} \"{e.title}\"  "
                   f"ysh={e.ysh_score:.1f} foreign={e.foreign_score:.1f} "
                   f"({', '.join((e.ysh_hits or []) + (e.foreign_hits or []))})"),
    )
    listing(
        "TITLE MISMATCH — audio announces a different story",
        mismatch,
        lambda e: (f"  {e.episode_id:>7} \"{e.title}\"\n"
                   f"          announced: \"{e.best_title}\" "
                   f"({e.best_score:.2f}, own {e.own_score:.2f}, {e.segment})"
                   + (f"  album: {e.best_album}" if e.best_album else "")),
    )
    listing(
        "CATALOG DISAGREEMENT — row vs yourstoryhour.org",
        meta_title + [e for e in meta_album if e not in meta_title],
        lambda e: (f"  {e.episode_id:>7} sku {e.sku_id}\n"
                   f"          row     : \"{e.title}\" / {e.album}\n"
                   f"          catalog : \"{e.catalog_title}\" / {e.catalog_album}"),
    )
    listing(
        "SKU NOT IN CATALOG — stale sku or bad external_id",
        off_catalog,
        lambda e: f"  {e.episode_id:>7} sku {e.sku_id} \"{e.title}\"",
    )
    listing(
        "ERRORS",
        errored,
        lambda e: f"  {e.episode_id:>7} \"{e.title}\": {e.error}",
    )

    needs_review = {e.episode_id for e in
                    foreign + ambiguous + mismatch + meta_title + meta_album
                    + off_catalog + errored}
    w("\n" + "=" * 72 + "\n")
    w(f"  {len(needs_review)} episode(s) need review, "
      f"{len(clean)} clean, {len(inconclusive)} inconclusive\n\n")
    return len(needs_review)


def _audit_candidates(
    catalog: list[YshTrack],
    archive_titles: Iterable[str],
) -> list[str]:
    """Build the title candidate set for the audio matcher.

    Titles come from the full yourstoryhour.org catalog PLUS the titles
    already in the archive: the catalog alone misses rows whose story
    was never scraped, and the archive alone (what `validate` uses) can
    only spot swaps between two episodes we already hold.

    Candidates that survive normalization poorly are dropped and
    counted — the catalog carries 182 Russian-language tracks that
    `_norm` reduces to "" or a bare digit, and a candidate normalizing
    to "2" matches essentially every transcript perfectly.
    """
    raw = sorted({t.title for t in catalog} | {t for t in archive_titles if t})
    usable = [t for t in raw if _matchable(_norm(t))]
    dropped = len(raw) - len(usable)
    if dropped:
        sys.stderr.write(
            f"[audit-ysh] {len(usable)} usable candidate title(s); "
            f"dropped {dropped} that normalize to nothing matchable "
            f"(non-Latin script or too short)\n"
        )
    return usable


def cmd_audit_ysh(args: argparse.Namespace) -> int:
    """Full-library YSH audit: provenance + title + catalog metadata.

    Read-only on the server — it never PATCHes and never stamps
    title_validated_at. Feed a mismatch it finds into `apply` (or fix
    by hand) once a human agrees with the call.
    """
    if args.from_report:
        raw = json.loads(args.from_report.read_text())
        entries = [YshAuditEntry(**e) for e in raw]
        if args.rescore:
            # Re-run the matcher over the stored transcripts. No server,
            # no GPU — the point is to try a new threshold (or a fixed
            # matcher) against an audit that already cost a GPU hour.
            catalog = load_ysh_catalog(args.catalog)
            album_of = {t.title: t.album for t in catalog}
            candidates = _audit_candidates(catalog,
                                           [e.title for e in entries])
            rescored = 0
            for e in entries:
                if not (e.head_transcript or e.tail_transcript):
                    continue
                score_entry(e, candidates, album_of, threshold=args.threshold)
                rescored += 1
            sys.stderr.write(f"[audit-ysh] re-scored {rescored} entr(ies) "
                             f"from stored transcripts\n")
            if args.out:
                args.out.write_text(
                    json.dumps([asdict(e) for e in entries], indent=2))
                sys.stderr.write(f"[audit-ysh] wrote → {args.out}\n")
        return 1 if (_print_ysh_audit_summary(entries, args.threshold)
                     and args.strict) else 0

    client = ArchiveClient(args.base_url, args.token)
    catalog = load_ysh_catalog(args.catalog)
    by_sku = {t.sku_id: t for t in catalog if t.sku_id is not None}
    album_of = {t.title: t.album for t in catalog}

    rows = [ep for ep in client.list_all_episodes(album=args.album)
            if (ep.get("provider_id") or "").lower() == "ysh"]
    rows.sort(key=lambda e: e["episode_id"])
    if args.offset:
        rows = rows[args.offset:]
    if args.limit:
        rows = rows[:args.limit]
    sys.stderr.write(f"[audit-ysh] {len(rows)} YSH episode(s); "
                     f"catalog has {len(catalog)} track(s)\n")

    entries = [_ysh_metadata_check(ep, by_sku) for ep in rows]

    if not args.no_audio:
        candidates = _audit_candidates(catalog, [e.title for e in entries])
        cfg = WhisperxConfig(
            pve=args.pve, ct=args.whisperx_ct, bin=args.whisperx_bin,
            model=args.model, device=args.device,
            compute_type=args.compute_type,
        )
        batches = list(_chunked(entries, args.batch_size))
        for i, batch in enumerate(batches, start=1):
            sys.stderr.write(f"[batch {i:>3}/{len(batches)}] "
                             f"{len(batch)} episode(s) ")
            sys.stderr.flush()
            _audit_batch(
                batch, client, cfg, candidates, album_of,
                head_secs=args.head_secs, tail_secs=args.tail_secs,
                threshold=args.threshold,
            )

    if args.out:
        args.out.write_text(json.dumps([asdict(e) for e in entries], indent=2))
        sys.stderr.write(f"[audit-ysh] wrote {len(entries)} entries → {args.out}\n")
    needs_review = _print_ysh_audit_summary(entries, args.threshold)
    return 1 if (needs_review and args.strict) else 0


# ---------------------------------------------------------------------------
# CLI plumbing
# ---------------------------------------------------------------------------


def _add_shared(p: argparse.ArgumentParser, *, needs_pve: bool = False) -> None:
    p.add_argument("--base-url", default=os.environ.get("ODYSSEY_BASE_URL"),
                   help="archive-service base URL (env ODYSSEY_BASE_URL)")
    p.add_argument("--token", default=os.environ.get("ODYSSEY_AUTH_TOKEN"),
                   help="bearer token (env ODYSSEY_AUTH_TOKEN)")
    p.add_argument("--album", default=None,
                   help="restrict to a single album by name")
    if needs_pve:
        p.add_argument("--pve", default=os.environ.get(
            "ODYSSEY_PVE_HOST", "root@192.168.2.123"))
        p.add_argument("--whisperx-ct", type=int, default=int(
            os.environ.get("ODYSSEY_WHISPERX_CT", "112")))
        p.add_argument("--whisperx-bin", default=os.environ.get(
            "ODYSSEY_WHISPERX_BIN", "/root/whisper-venv/bin/whisperx"))
        p.add_argument("--model", default="large-v3")
        p.add_argument("--device", default="cuda")
        p.add_argument("--compute-type", dest="compute_type", default="float16")


def _parse() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)

    v = sub.add_parser("validate", help="Transcribe tails, write report")
    _add_shared(v, needs_pve=True)
    v.add_argument("--out", type=Path, required=True,
                   help="report JSON output path")
    v.add_argument("--catalog", type=Path,
                   default=Path(__file__).resolve().parent.parent / "aio_catalog.json",
                   help="path to aio_catalog.json")
    v.add_argument("--tail-secs", type=int, default=30,
                   help="seconds of tail audio for AIO episodes (title-at-end)")
    v.add_argument("--head-secs", type=int, default=90,
                   help="seconds of head audio for YSH episodes "
                        "(title-at-start; some hosts pad the cold-open "
                        "out past 60s so 90 is a safer default)")
    v.add_argument("--limit", type=int, default=0,
                   help="cap on episodes to process (0 = all)")
    v.add_argument("--offset", type=int, default=0,
                   help="skip the first N episodes (for paging deeper)")
    v.add_argument("--unsorted", action="store_true",
                   help="restrict to episodes with no album (likely "
                        "imported-from-disk; most likely to be mis-titled)")
    v.add_argument("--revalidate", action="store_true",
                   help="re-validate rows whose title_validated_at is "
                        "already set (default: skip them)")
    v.add_argument("--batch-size", type=int, default=25,
                   help="episodes per whisperx invocation. The large-v3 "
                        "model takes ~30s to load on CT 112's GPU; "
                        "batching amortizes that cost across all clips "
                        "in the batch. Larger = faster end-to-end but a "
                        "single failure loses the whole batch.")
    v.set_defaults(func=cmd_validate)

    p = sub.add_parser(
        "plan",
        help="Preview proposed changes + summary stats (no server writes)",
    )
    p.add_argument("--report", type=Path, required=True)
    p.add_argument("--threshold", type=float, default=0.95,
                   help="min confidence required to propose (0..1). "
                        "Default 0.95 admits only substring hits + the "
                        "tightest fuzzy near-matches; pre-2026-06 default "
                        "of 0.85 surfaced false positives where the matcher "
                        "latched onto random credit-phrase words.")
    p.add_argument("--top", type=int, default=0,
                   help="show only the top-N proposed changes (0 = all)")
    p.set_defaults(func=cmd_plan)

    a = sub.add_parser("apply", help="PATCH titles from a report")
    _add_shared(a)
    a.add_argument("--report", type=Path, required=True)
    a.add_argument("--threshold", type=float, default=0.95,
                   help="min confidence required to apply (0..1)")
    a.add_argument("--fix-album", action="store_true",
                   help="also write the catalog album when patching")
    a.add_argument("--dry-run", action="store_true")
    a.set_defaults(func=cmd_apply)

    d = sub.add_parser("dedup", help="Report (and optionally delete) duplicates")
    _add_shared(d)
    d.add_argument("--delete-smaller", action="store_true")
    d.add_argument("--dry-run", action="store_true")
    d.set_defaults(func=cmd_dedup)

    ay = sub.add_parser(
        "audit-ysh",
        help="Sweep the YSH library: is each episode really YSH, and "
             "does the announced title match the row? (read-only)",
    )
    _add_shared(ay, needs_pve=True)
    ay.add_argument("--catalog", type=Path,
                    default=(Path(__file__).resolve().parent.parent.parent
                             / "docs" / "ysh-probe"
                             / "yourstoryhour-tracks-flat.json"),
                    help="YSH track catalog: either the flat probe JSON "
                         "or a scrape_ysh-format ysh_catalog.json")
    ay.add_argument("--out", type=Path, default=None,
                    help="write the full JSON report here")
    ay.add_argument("--from-report", type=Path, default=None,
                    help="re-print the summary from an existing report "
                         "instead of re-transcribing (no server, no GPU)")
    ay.add_argument("--rescore", action="store_true",
                    help="with --from-report: re-run the matcher over the "
                         "report's stored transcripts, so a new --threshold "
                         "(or a fixed matcher) can be tried without paying "
                         "for another GPU pass. Writes back with --out.")
    ay.add_argument("--threshold", type=float, default=0.9,
                    help="min fuzzy score to confirm a title (0..1). "
                         "Lower than validate's 0.95 because a confirm "
                         "here only marks a row clean; a mismatch still "
                         "has to beat the row's own title to be raised.")
    ay.add_argument("--head-secs", type=int, default=180,
                    help="seconds of head audio (carries 'I call my story, X'). "
                         "Longer than validate's 90 on measured evidence: YSH "
                         "cold-opens routinely push the credit line past the "
                         "90s mark, and a clip that stops short reads as an "
                         "unconfirmable title rather than a confirmed one.")
    ay.add_argument("--tail-secs", type=int, default=45,
                    help="seconds of tail audio (carries the YSH station ID "
                         "that decides provenance, plus the closing credit)")
    ay.add_argument("--limit", type=int, default=0,
                    help="cap episodes audited (0 = all). Use for a smoke run.")
    ay.add_argument("--offset", type=int, default=0,
                    help="skip the first N episodes")
    ay.add_argument("--batch-size", type=int, default=25,
                    help="episodes per whisperx invocation (2 clips each)")
    ay.add_argument("--no-audio", action="store_true",
                    help="metadata-only pass: compare every row against the "
                         "yourstoryhour.org catalog. Seconds, no GPU.")
    ay.add_argument("--strict", action="store_true",
                    help="exit 1 when any episode needs review (for cron)")
    ay.set_defaults(func=cmd_audit_ysh)

    args = parser.parse_args()
    if args.cmd == "audit-ysh" and args.from_report:
        return args
    if args.cmd in ("validate", "apply", "dedup", "audit-ysh"):
        if not args.base_url:
            parser.error("--base-url is required (or set ODYSSEY_BASE_URL)")
        if not args.token:
            parser.error("--token is required (or set ODYSSEY_AUTH_TOKEN)")
    return args


# Re-export for tests so they can call cmd_plan(args) without a full
# CLI roundtrip — keeps the bucket/sort logic verifiable.
__all__ = ["cmd_plan", "cmd_apply", "cmd_dedup", "cmd_validate",
           "cmd_audit_ysh", "best_catalog_match", "load_catalog",
           "CatalogEpisode", "load_ysh_catalog", "YshTrack",
           "ysh_provenance", "ysh_title_verdict", "ysh_title_variants",
           "score_markers", "sku_id_from_external_id"]


def main() -> int:
    args = _parse()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
