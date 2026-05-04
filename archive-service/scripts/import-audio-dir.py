#!/usr/bin/env python3
"""
Bulk-import a directory of MP3 audio files into archive-service.

Walks the directory recursively, attempts to derive a (title, airDate)
tuple from each file (filename heuristics + ID3 tags if mutagen is
installed), and POSTs each file to the archive-service /episodes
endpoint.

Idempotent: if the server already has an episode with the same
episodeId, it returns 200 and we move on. Caller can re-run safely.

Designed to import the user's existing pile of MP3s downloaded by
the original C# Adventures-In-Odyssey-Downloader (the .cs files at
the repo root). Filenames there look like "1234-Episode Title.mp3" or
"Episode Title.mp3" — both are handled.

Usage:
    archive-service/scripts/import-audio-dir.py \\
        --dir /path/to/old/episodes \\
        --base-url http://192.168.2.50:8088 \\
        --token $(cat ~/.aio-archive-token)

Optional ID3 metadata extraction needs `mutagen`:
    pip install --user mutagen
"""
from __future__ import annotations
import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

try:
    from mutagen.id3 import ID3, ID3NoHeaderError  # type: ignore
    from mutagen.mp3 import MP3  # type: ignore
    HAVE_MUTAGEN = True
except ImportError:
    HAVE_MUTAGEN = False

CHUNK = 1 << 20  # 1 MiB


@dataclass
class ImportItem:
    path: Path
    title: str
    air_date: Optional[str]
    duration_ms: int
    episode_id: int  # synthesized when not derivable from filename


# Filename patterns we know how to parse — most-specific first.
FILENAME_PATTERNS: list[re.Pattern] = [
    # "1234-Episode Title.mp3" — original C# downloader format
    re.compile(r"^(?P<id>\d{2,7})\s*[-_]\s*(?P<title>.+?)\.(?:mp3|m4a)$", re.IGNORECASE),
    # "Episode Title (1234).mp3"
    re.compile(r"^(?P<title>.+?)\s*\((?P<id>\d{2,7})\)\.(?:mp3|m4a)$", re.IGNORECASE),
    # bare "Episode Title.mp3"
    re.compile(r"^(?P<title>.+?)\.(?:mp3|m4a)$", re.IGNORECASE),
]


def _synth_id(path: Path) -> int:
    """Stable synthetic episode id when the filename has none — first
    7 hex digits of MD5(path), interpreted as int. Collision risk is
    negligible at this scale."""
    digest = hashlib.md5(str(path.resolve()).encode("utf-8")).hexdigest()
    # Top bit clear so it fits a Java/SQLite Long without sign weirdness.
    return int(digest[:7], 16)


def parse_filename(path: Path) -> tuple[str, Optional[int]]:
    name = path.name
    for pat in FILENAME_PATTERNS:
        m = pat.match(name)
        if m:
            title = m.group("title").strip().replace("_", " ")
            try:
                eid: Optional[int] = int(m.group("id")) if "id" in m.groupdict() else None
            except (ValueError, IndexError):
                eid = None
            return title, eid
    return path.stem, None


def parse_id3(path: Path) -> tuple[Optional[str], Optional[str], int]:
    """Returns (title, air_date_iso, duration_ms). All fields optional."""
    if not HAVE_MUTAGEN:
        return None, None, 0
    title = None
    air_date = None
    dur_ms = 0
    try:
        try:
            tags = ID3(path)
            if "TIT2" in tags:
                title = str(tags["TIT2"]).strip() or None
            # TDRC, TYER are common date frames
            for k in ("TDRC", "TDOR", "TYER"):
                if k in tags:
                    air_date = str(tags[k]).strip() or None
                    if air_date:
                        break
        except ID3NoHeaderError:
            pass
        try:
            audio = MP3(path)
            if audio.info and audio.info.length:
                dur_ms = int(audio.info.length * 1000)
        except Exception:
            pass
    except Exception:
        pass
    return title, air_date, dur_ms


def collect(root: Path) -> Iterable[ImportItem]:
    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        if path.suffix.lower() not in (".mp3", ".m4a"):
            continue
        title_fn, id_fn = parse_filename(path)
        title_id3, air_date_id3, dur_ms = parse_id3(path)
        title = title_id3 or title_fn or path.stem
        episode_id = id_fn if id_fn is not None else _synth_id(path)
        yield ImportItem(
            path=path,
            title=title,
            air_date=air_date_id3,
            duration_ms=dur_ms,
            episode_id=episode_id,
        )


def upload(item: ImportItem, base_url: str, token: str, dry_run: bool) -> tuple[int, str]:
    """POST a single episode + audio file to archive-service.

    Endpoint shape (matches what the Android NasClient expects):
        POST {base}/episodes
        Authorization: Bearer <token>
        multipart/form-data fields:
            metadata (json): {"episodeId":N,"title":...,"airDate":...,"durationMs":N}
            audio (file): the MP3 bytes
    """
    metadata = json.dumps({
        "episodeId": item.episode_id,
        "title": item.title,
        "airDate": item.air_date,
        "durationMs": item.duration_ms,
    }).encode("utf-8")

    if dry_run:
        return 0, f"DRY  ep={item.episode_id} {item.title!r}  ({item.path.stat().st_size} bytes)"

    boundary = "----aio-import-" + hashlib.md5(str(item.path).encode()).hexdigest()[:8]
    head = (
        f"--{boundary}\r\n"
        f"Content-Disposition: form-data; name=\"metadata\"\r\n"
        f"Content-Type: application/json\r\n\r\n"
    ).encode("ascii") + metadata + b"\r\n"
    file_head = (
        f"--{boundary}\r\n"
        f"Content-Disposition: form-data; name=\"audio\"; filename=\"{item.path.name}\"\r\n"
        f"Content-Type: audio/mpeg\r\n\r\n"
    ).encode("ascii")
    tail = f"\r\n--{boundary}--\r\n".encode("ascii")

    body = head + file_head + item.path.read_bytes() + tail
    req = Request(
        f"{base_url.rstrip('/')}/episodes",
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
            "User-Agent": "odyssey-import/0.1",
        },
    )
    try:
        with urlopen(req, timeout=120) as r:
            return r.status, f"OK   {r.status}  ep={item.episode_id} {item.title!r}"
    except HTTPError as e:
        return e.code, f"FAIL {e.code}  ep={item.episode_id} {item.title!r}: {e.read()[:200]!r}"
    except URLError as e:
        return -1, f"FAIL net   ep={item.episode_id} {item.title!r}: {e}"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--dir", required=True, type=Path, help="root directory of MP3s")
    p.add_argument("--base-url", required=True, help="archive-service base URL, e.g. http://host:8088")
    p.add_argument("--token", required=True, help="bearer token for archive-service")
    p.add_argument("--dry-run", action="store_true", help="print what would be uploaded")
    p.add_argument("--limit", type=int, default=0, help="stop after N items (0 = no limit)")
    args = p.parse_args()

    if not args.dir.is_dir():
        print(f"error: {args.dir} is not a directory", file=sys.stderr)
        return 2

    n_ok = 0
    n_fail = 0
    items = list(collect(args.dir))
    print(f"found {len(items)} audio files in {args.dir}")
    if not HAVE_MUTAGEN:
        print("note: mutagen not installed — falling back to filename-only metadata. "
              "`pip install --user mutagen` for ID3 + duration extraction.", file=sys.stderr)

    for i, item in enumerate(items, 1):
        if args.limit and i > args.limit:
            print(f"-- limit {args.limit} reached, stopping --")
            break
        status, msg = upload(item, args.base_url, args.token, args.dry_run)
        print(f"[{i:4}/{len(items)}] {msg}")
        if 200 <= status < 300 or args.dry_run:
            n_ok += 1
        else:
            n_fail += 1

    print(f"\nDone. ok={n_ok} fail={n_fail}")
    return 0 if n_fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
