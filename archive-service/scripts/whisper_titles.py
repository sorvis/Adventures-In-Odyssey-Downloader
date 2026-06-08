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


def best_ysh_match(
    transcript: str,
    candidates: list[str],
) -> tuple[str | None, float]:
    """Anchor-first matcher for YSH episodes.

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
        return None, 0.0
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
            if not cn:
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
            return best

    # Fallback: anchor not found → wider head scan. Older YSH cuts
    # sometimes use a music intro that obscures the credit line.
    tn_words = tn.split()
    head = tn_words[:200]
    head_str = " ".join(head)
    best = (None, 0.0)
    for title in candidates:
        cn = _norm(title)
        if not cn:
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
    return best


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
    # Restrict ALL matching to the tail of the transcript — the credit
    # is in the closing seconds, and an earlier name-drop ("…just like
    # in Knox on Money…") shouldn't outscore the actual credit phrase.
    # 50 words covers the typical closing announcement plus a buffer.
    tail = tn_words[-50:]
    tail_str = " ".join(tail)
    best: tuple[CatalogEpisode | None, float] = (None, 0.0)
    for entry in catalog:
        cn = _norm(entry.title)
        if not cn:
            continue
        # Exact substring within the tail — cheapest, strongest.
        # Tie-break (if multiple catalog titles all appear in the tail)
        # goes to the longest match; rare in practice but keeps the
        # behavior deterministic.
        if cn in tail_str:
            score = 1.0
            if score > best[1] or (score == best[1] and (best[0] is None or len(cn) > len(_norm(best[0].title)))):
                best = (entry, score)
            continue
        cn_words = cn.split()
        if not cn_words or len(cn_words) > len(tail):
            continue
        max_local = 0.0
        for i in range(0, len(tail) - len(cn_words) + 1):
            window = " ".join(tail[i : i + len(cn_words)])
            r = difflib.SequenceMatcher(None, window, cn).ratio()
            if r > max_local:
                max_local = r
        if max_local > best[1]:
            best = (entry, max_local)
    return best


# ---------------------------------------------------------------------------
# Archive-service HTTP client
# ---------------------------------------------------------------------------


class ArchiveClient:
    def __init__(self, base_url: str, token: str):
        self.base = base_url.rstrip("/")
        self.token = token

    def _req(self, method: str, path: str, *, data: bytes | None = None,
             json_body: dict | None = None) -> bytes:
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
        /episodes?album= because the filter semantics differ for NULL."""
        return json.loads(self._req("GET", "/albums/Unsorted/episodes"))

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

    def mark_validated(self, episode_id: int) -> None:
        """Stamp title_validated_at on the row. Best-effort: a 404 on
        an old server (no endpoint yet) is logged-and-swallowed so the
        validate run can finish."""
        try:
            self._req("PUT", f"/episodes/{episode_id}/title-validated")
        except HTTPError as e:
            if e.code == 404:
                sys.stderr.write(
                    f"  (server has no title-validated endpoint; skipping stamp)\n"
                )
                return
            raise

    def delete_episode(self, episode_id: int) -> None:
        self._req("DELETE", f"/episodes/{episode_id}")


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
        if ep.get("title_validated_at") and not args.revalidate:
            skipped_already_validated += 1
            continue
        queue.append(ep)
    if skipped_already_validated:
        sys.stderr.write(
            f"[validate] skipping {skipped_already_validated} already-validated "
            f"row(s); pass --revalidate to re-check\n"
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
                client.mark_validated(eid)
            except Exception as exc:
                sys.stderr.write(f"    (mark_validated: {exc})\n")
    return rows + download_errors


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

    args = parser.parse_args()
    if args.cmd in ("validate", "apply", "dedup"):
        if not args.base_url:
            parser.error("--base-url is required (or set ODYSSEY_BASE_URL)")
        if not args.token:
            parser.error("--token is required (or set ODYSSEY_AUTH_TOKEN)")
    return args


# Re-export for tests so they can call cmd_plan(args) without a full
# CLI roundtrip — keeps the bucket/sort logic verifiable.
__all__ = ["cmd_plan", "cmd_apply", "cmd_dedup", "cmd_validate",
           "best_catalog_match", "load_catalog", "CatalogEpisode"]


def main() -> int:
    args = _parse()
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
