"""
One-shot NAS layout migration.

Legacy archive-service stored AIO downloads at
`<AUDIO_DIR>/<album-slug>/...mp3`. Multi-show prep moves AIO under
`<AUDIO_DIR>/aio/<album-slug>/...mp3` so YSH content can sit alongside
under `<AUDIO_DIR>/ysh/...` without colliding.

Runs once on FastAPI startup. Sentinel-gated by a zero-byte marker
file `<AUDIO_DIR>/.aio-layout-v1`: presence means migration is done,
so the function is a fast no-op on every subsequent start.

Behavior:
  - If the marker exists: return immediately.
  - Otherwise:
      1. Enumerate top-level directories under AUDIO_DIR that aren't
         reserved (anything but "aio", "ysh", "_*" hidden, etc.).
      2. mkdir AUDIO_DIR/aio/
      3. shutil.move() each legacy slug dir into aio/.
      4. UPDATE episodes SET file_path = REPLACE(file_path,
         '<AUDIO_DIR>/<slug>/', '<AUDIO_DIR>/aio/<slug>/')
         for every legacy row.
      5. Create the marker.
"""
from __future__ import annotations

import logging
import shutil
from pathlib import Path

from . import db
from .config import AUDIO_DIR

log = logging.getLogger(__name__)

_MARKER_NAME = ".aio-layout-v1"
# Top-level entries that are NOT legacy slug dirs — leave them alone
# even on a first-run migration. "aio" and "ysh" are the post-migration
# layout; the underscore-prefixed names cover ad-hoc trash like
# "_unmatched" or "_quarantine" that the user may have created.
_RESERVED_NAMES = {"aio", "ysh"}


def migrate_layout() -> None:
    """Idempotent. Safe to call on every app startup."""
    AUDIO_DIR.mkdir(parents=True, exist_ok=True)
    marker = AUDIO_DIR / _MARKER_NAME
    if marker.exists():
        return

    legacy_dirs = [
        p for p in AUDIO_DIR.iterdir()
        if p.is_dir()
        and p.name not in _RESERVED_NAMES
        and not p.name.startswith("_")
        and not p.name.startswith(".")
    ]
    if not legacy_dirs:
        # Fresh install (or already migrated and marker got wiped).
        # Drop the marker so subsequent starts skip the scan.
        marker.touch()
        return

    aio_dir = AUDIO_DIR / "aio"
    aio_dir.mkdir(exist_ok=True)
    moved = 0
    for src in legacy_dirs:
        dst = aio_dir / src.name
        if dst.exists():
            # A same-name dir already lives under aio/. Merge by
            # moving any files that don't yet exist on the dst side;
            # leave the src behind for the operator to clean up.
            for child in src.iterdir():
                target = dst / child.name
                if not target.exists():
                    shutil.move(str(child), str(target))
            continue
        shutil.move(str(src), str(dst))
        moved += 1

    _rewrite_file_paths()
    marker.touch()
    log.info(
        "NAS layout migration complete: moved=%s legacy_dirs (now under audio/aio/)",
        moved,
    )


def _rewrite_file_paths() -> None:
    """Rewrite `episodes.file_path` rows that point at the legacy
    top-level layout to the new audio/aio/<slug>/... layout. Uses a
    SQL REPLACE so unrelated paths (e.g. rows whose audio is gone or
    rows already under aio/) pass through untouched."""
    audio_prefix = f"{AUDIO_DIR}/"
    aio_prefix = f"{AUDIO_DIR}/aio/"
    with db.connect() as c:
        rows = c.execute(
            "SELECT episode_id, file_path FROM episodes WHERE file_path LIKE ?",
            (f"{audio_prefix}%",),
        ).fetchall()
        for row in rows:
            old = row["file_path"]
            # Skip paths that already live under aio/ (idempotent re-run).
            relative = old[len(audio_prefix):]
            if relative.startswith("aio/") or relative.startswith("ysh/"):
                continue
            new = aio_prefix + relative
            c.execute(
                "UPDATE episodes SET file_path = ? WHERE episode_id = ?",
                (new, row["episode_id"]),
            )
