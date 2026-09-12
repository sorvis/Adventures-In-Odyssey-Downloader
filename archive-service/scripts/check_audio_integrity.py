#!/usr/bin/env python3
"""
Detect truncated or corrupt episode audio in the NAS archive.

The failure this exists to catch: a download that stopped partway
through, leaving a playable-looking MP3 that simply ends in the middle
of the story. Nothing else in the pipeline notices — the row archives
fine, the player starts fine, and you only find out when the audio cuts
out mid-sentence.

## How truncation is detected

Every file in the archive carries a Xing/Info header in its first
frame, written by the encoder. That header declares how many frames and
how many BYTES the complete file should contain. So the decisive test
is a comparison the file makes against itself:

    declared audio bytes (from the Info header)
      vs.
    actual audio bytes (real file size - ID3 tags)

A short file is a truncated file. This needs only the first few KB of
each episode plus its size, so a full sweep of the archive costs a few
hundred small HTTP range requests rather than ~10 GB of downloads, and
needs no ffmpeg anywhere.

Three further checks ride along for free, since the header also yields
the intended duration:

  * duration outliers  - an episode far shorter than its provider's
                         median. Catches files whose header was itself
                         written against truncated input, which the
                         self-comparison above cannot see.
  * size mismatch      - the size the DB recorded at archive time vs
                         the size on disk now. Catches a file that was
                         complete when archived and lost bytes since.
  * missing header     - a file with no Xing/Info frame can't be checked
                         against itself, so its duration is derived from
                         its constant bitrate and judged against its
                         peers instead. Noted in the report so you know
                         which verdicts rest on the weaker test.

`--deep` adds a full-body read per episode to verify the stored sha256,
which catches on-disk corruption that preserves length. That one does
move the whole archive over the network, so it's opt-in.

Read-only. It reports; it never deletes or re-downloads.

Usage:
  scripts/check_audio_integrity.py --base-url URL --token TOK
  scripts/check_audio_integrity.py ... --provider ysh --json
  scripts/check_audio_integrity.py ... --deep        # + sha256 verify

Exit codes: 0 = every file intact, 1 = at least one problem,
            2 = transport/credential error.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import statistics
import sys
from dataclasses import dataclass, asdict, field
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


# ---------------------------------------------------------------------------
# MP3 header parsing
# ---------------------------------------------------------------------------
#
# Imported from the service package rather than duplicated here: the
# upload path in app/routes/ enforces the same rule on every incoming
# episode, and two copies of this parser would drift into disagreeing
# about what "complete" means — the sweep passing files the ingest gate
# rejects, or worse, the reverse.
_SVC_ROOT = Path(__file__).resolve().parent.parent
if str(_SVC_ROOT) not in sys.path:
    sys.path.insert(0, str(_SVC_ROOT))

from app.audio_integrity import (      # noqa: E402
    BAD,
    UNKNOWN,
    Integrity,
    XingHeader,
    FrameInfo,
    declared_duration_secs,
    find_first_frame,
    id3v2_size,
    inspect_bytes,
    parse_frame_header,
    parse_xing,
)


# ---------------------------------------------------------------------------
# HTTP client
# ---------------------------------------------------------------------------


class AudioClient:
    def __init__(self, base_url: str, token: str):
        self.base = base_url.rstrip("/")
        self.token = token

    def _get(self, path: str, extra: dict | None = None):
        headers = {"Authorization": f"Bearer {self.token}"}
        headers.update(extra or {})
        return urlopen(Request(self.base + path, headers=headers), timeout=60)

    def list_all_episodes(self, page_size: int = 500):
        offset = 0
        while True:
            page = json.loads(self._get(
                f"/episodes?limit={page_size}&offset={offset}").read())
            if not page:
                return
            yield from page
            if len(page) < page_size:
                return
            offset += page_size

    def head_bytes(self, episode_id: int, n: int) -> tuple[bytes, int | None]:
        """First `n` bytes of an episode plus its true total size.

        The size comes from the Content-Range of the same request, so
        this is the size of the file ON DISK right now — not the size
        the database recorded when it was archived. Comparing the two
        is what catches a file that lost bytes after archiving.
        """
        r = self._get(f"/episodes/{episode_id}/audio",
                      {"Range": f"bytes=0-{n - 1}"})
        data = r.read()
        total = None
        cr = r.headers.get("Content-Range")
        if cr and "/" in cr:
            tail = cr.rsplit("/", 1)[1].strip()
            if tail.isdigit():
                total = int(tail)
        if total is None:
            cl = r.headers.get("Content-Length")
            if cl and cl.isdigit() and r.status == 200:
                total = int(cl)
        return data, total

    def sha256(self, episode_id: int) -> str:
        h = hashlib.sha256()
        r = self._get(f"/episodes/{episode_id}/audio")
        while chunk := r.read(1 << 20):
            h.update(chunk)
        return h.hexdigest()


# ---------------------------------------------------------------------------
# Per-episode check
# ---------------------------------------------------------------------------


@dataclass
class Finding:
    episode_id: int
    title: str
    provider: str
    problems: list[str] = field(default_factory=list)
    declared_bytes: int | None = None
    actual_audio_bytes: int | None = None
    missing_bytes: int | None = None
    declared_secs: float | None = None
    db_size: int | None = None
    disk_size: int | None = None
    notes: list[str] = field(default_factory=list)
    error: str | None = None

    @property
    def ok(self) -> bool:
        return not self.problems and self.error is None


def check_episode(client: AudioClient, ep: dict, *,
                  probe_bytes: int) -> Finding:
    """Header-level integrity check for one episode. Cheap: reads only
    the first `probe_bytes` of the file."""
    f = Finding(
        episode_id=ep["episode_id"],
        title=ep.get("title") or "",
        provider=(ep.get("provider_id") or "aio").lower(),
        db_size=ep.get("file_size"),
    )
    try:
        head, total = client.head_bytes(ep["episode_id"], probe_bytes)
    except (HTTPError, URLError, OSError) as exc:
        f.error = f"fetch: {exc}"
        return f
    f.disk_size = total

    tag_len = id3v2_size(head)
    if tag_len >= len(head):
        # An oversized ID3 tag (cover art) pushed the audio past our
        # probe window; re-fetch a window that starts at the audio.
        try:
            head2, _ = client.head_bytes(ep["episode_id"], tag_len + 4096)
            head = head2
        except (HTTPError, URLError, OSError) as exc:
            f.error = f"fetch(tag={tag_len}): {exc}"
            return f

    frame = find_first_frame(head, tag_len)
    if frame is None:
        f.problems.append("no MPEG frame found")
        return f

    xing = parse_xing(head, frame)
    if xing is None:
        # No self-declared length, so the file can't be checked against
        # itself. It can still be checked against its peers: these are
        # constant-bitrate files, so bytes/bitrate gives a real duration
        # that the outlier pass can judge. A truncated CBR file reports
        # a correspondingly short duration, which is exactly the signal
        # we need — it just can't be caught as precisely.
        f.notes.append("no Xing/Info header; duration estimated from "
                       f"CBR {frame.bitrate_kbps} kbps")
        if total is not None and frame.bitrate_kbps:
            audio_bytes = total - frame.frame_offset
            f.actual_audio_bytes = audio_bytes
            f.declared_secs = audio_bytes / (frame.bitrate_kbps * 1000 / 8)
        if f.db_size is not None and total is not None and total != f.db_size:
            f.problems.append(
                f"size drift: on disk {total:,} vs {f.db_size:,} in DB")
        return f

    f.declared_bytes = xing.byte_count
    f.declared_secs = declared_duration_secs(frame, xing)

    if total is not None:
        # The Info header's byte count covers the audio stream, which
        # starts at the Xing frame itself — so compare against the file
        # size minus everything before that frame.
        f.actual_audio_bytes = total - frame.frame_offset
        if xing.byte_count:
            missing = xing.byte_count - f.actual_audio_bytes
            # A handful of trailing bytes is normal slack (ID3v1 at the
            # end, encoder padding); only a real shortfall counts.
            if missing > 2048:
                f.missing_bytes = missing
                pct = 100.0 * missing / xing.byte_count
                f.problems.append(
                    f"TRUNCATED: {missing:,} bytes short of the "
                    f"{xing.byte_count:,} its header declares ({pct:.1f}%)")
        if f.db_size is not None and total != f.db_size:
            f.problems.append(
                f"size drift: on disk {total:,} vs {f.db_size:,} in DB")
    return f


def flag_duration_outliers(findings: list[Finding], ratio: float) -> None:
    """Flag episodes far shorter than their provider's median.

    Complements the header self-check, which is blind to a file whose
    header was written against already-truncated input — there the
    declared length agrees with the short file, and only comparing
    against its peers reveals it.
    """
    by_provider: dict[str, list[float]] = {}
    for f in findings:
        if f.declared_secs:
            by_provider.setdefault(f.provider, []).append(f.declared_secs)
    medians = {p: statistics.median(v)
               for p, v in by_provider.items() if len(v) >= 5}
    for f in findings:
        med = medians.get(f.provider)
        if med and f.declared_secs and f.declared_secs < med * ratio:
            f.problems.append(
                f"SHORT: {f.declared_secs / 60:.1f} min vs "
                f"{med / 60:.1f} min median for {f.provider}")


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------


def print_report(findings: list[Finding], *, ratio: float) -> int:
    w = sys.stdout.write
    bad = [f for f in findings if not f.ok]
    errored = [f for f in findings if f.error]
    truncated = [f for f in findings
                 if any(p.startswith("TRUNCATED") for p in f.problems)]
    short = [f for f in findings
             if any(p.startswith("SHORT") for p in f.problems)]
    other = [f for f in bad if f not in truncated and f not in short
             and f not in errored]

    estimated = [f for f in findings if f.notes and f.ok]
    durs = [f.declared_secs for f in findings if f.declared_secs]
    w(f"\nAudio integrity — {len(findings)} episode(s)\n")
    w("=" * 72 + "\n")
    w(f"  intact                                       : "
      f"{len(findings) - len(bad)}\n")
    w(f"  TRUNCATED (shorter than their own header)    : {len(truncated)}\n")
    w(f"  SHORT (well under the provider median)       : {len(short)}\n")
    w(f"  other problems                               : {len(other)}\n")
    w(f"  fetch errors                                 : {len(errored)}\n")
    if estimated:
        w(f"  (of the intact, {len(estimated)} verified by duration only —\n"
          f"   no Xing/Info header to self-check against)\n")
    if durs:
        w(f"\n  duration: median {statistics.median(durs)/60:.1f} min, "
          f"min {min(durs)/60:.1f}, max {max(durs)/60:.1f}\n")

    for label, rows in (("TRUNCATED — audio stops before its header says it should", truncated),
                        ("SHORT — suspiciously brief vs peers", short),
                        ("OTHER", other),
                        ("ERRORS", errored)):
        if not rows:
            continue
        w(f"\n{label} ({len(rows)})\n" + "-" * 72 + "\n")
        for f in rows:
            w(f"  {f.episode_id:>8} [{f.provider}] \"{f.title[:44]}\"\n")
            for p in f.problems:
                w(f"           {p}\n")
            if f.error:
                w(f"           {f.error}\n")

    w("\n" + "=" * 72 + "\n")
    w(f"  {len(bad)} episode(s) need attention, "
      f"{len(findings) - len(bad)} intact\n\n")
    return len(bad)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=os.environ.get("ODYSSEY_BASE_URL"))
    ap.add_argument("--token", default=os.environ.get("ODYSSEY_AUTH_TOKEN"))
    ap.add_argument("--provider", default=None,
                    help="restrict to one provider (aio / ysh)")
    ap.add_argument("--limit", type=int, default=0,
                    help="cap episodes checked (0 = all)")
    ap.add_argument("--probe-bytes", type=int, default=16384,
                    help="bytes read from the head of each file")
    ap.add_argument("--short-ratio", type=float, default=0.6,
                    help="flag episodes under this fraction of the "
                         "provider median duration")
    ap.add_argument("--deep", action="store_true",
                    help="also download each file in full and verify its "
                         "stored sha256 (catches corruption that keeps "
                         "the length intact). Moves the whole archive.")
    ap.add_argument("--json", action="store_true",
                    help="machine-readable output instead of the report")
    ap.add_argument("--out", default=None, help="write the JSON report here")
    args = ap.parse_args()
    if not args.base_url or not args.token:
        ap.error("--base-url and --token are required "
                 "(or ODYSSEY_BASE_URL / ODYSSEY_AUTH_TOKEN)")

    client = AudioClient(args.base_url, args.token)
    try:
        eps = list(client.list_all_episodes())
    except (HTTPError, URLError, OSError) as exc:
        sys.stderr.write(f"error: cannot list episodes: {exc}\n")
        return 2
    if args.provider:
        eps = [e for e in eps
               if (e.get("provider_id") or "").lower() == args.provider.lower()]
    eps.sort(key=lambda e: e["episode_id"])
    if args.limit:
        eps = eps[:args.limit]

    sys.stderr.write(f"[integrity] checking {len(eps)} episode(s)"
                     f"{' (deep: full download)' if args.deep else ''}\n")
    findings: list[Finding] = []
    for i, ep in enumerate(eps, start=1):
        f = check_episode(client, ep, probe_bytes=args.probe_bytes)
        if args.deep and f.error is None and ep.get("sha256"):
            try:
                if client.sha256(ep["episode_id"]) != ep["sha256"]:
                    f.problems.append("sha256 mismatch vs DB (corrupt on disk)")
            except (HTTPError, URLError, OSError) as exc:
                f.problems.append(f"sha256 verify failed: {exc}")
        findings.append(f)
        if i % 50 == 0:
            sys.stderr.write(f"  … {i}/{len(eps)}\n")

    flag_duration_outliers(findings, args.short_ratio)

    if args.out:
        with open(args.out, "w") as fh:
            json.dump([asdict(f) for f in findings], fh, indent=2)
        sys.stderr.write(f"[integrity] wrote report → {args.out}\n")
    if args.json:
        print(json.dumps([asdict(f) for f in findings], indent=2))
        return 1 if any(not f.ok for f in findings) else 0
    return 1 if print_report(findings, ratio=args.short_ratio) else 0


if __name__ == "__main__":
    raise SystemExit(main())
