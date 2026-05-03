import hashlib
import re
from pathlib import Path
from .config import AUDIO_DIR

_SLUG_RE = re.compile(r"[^a-z0-9]+")


def slugify(s: str) -> str:
    return _SLUG_RE.sub("-", s.lower()).strip("-") or "untitled"


def episode_path(episode_id: int, title: str, album: str | None) -> Path:
    album_dir = slugify(album) if album else "unsorted"
    fname = f"{episode_id}-{slugify(title)}.mp3"
    return AUDIO_DIR / album_dir / fname


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
