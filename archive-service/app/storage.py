import hashlib
import re
from pathlib import Path
from .config import AUDIO_DIR

_SLUG_RE = re.compile(r"[^a-z0-9]+")


def slugify(s: str) -> str:
    return _SLUG_RE.sub("-", s.lower()).strip("-") or "untitled"


def episode_path_for(
    provider_id: str,
    external_id: str,
    title: str,
    album: str | None,
) -> Path:
    """Provider-aware path. AIO episodes land under `audio/aio/<slug>/`
    so YSH content can live alongside under `audio/ysh/<slug>/` without
    colliding when externalIds happen to share a numeric range
    (sku_id 1958 collides with AIO broadcast 1958, etc.).

    Use `episode_path()` for the legacy AIO-only call shape — it
    delegates here with `provider_id="aio"` and stringifies the
    episode_id.
    """
    album_dir = slugify(album) if album else "unsorted"
    # YSH external_ids like "ysh-sku-1958" survive slugify intact
    # (dashes preserved); AIO ones are pure digits.
    fname = f"{slugify(external_id)}-{slugify(title)}.mp3"
    return AUDIO_DIR / provider_id / album_dir / fname


def episode_path(episode_id: int, title: str, album: str | None) -> Path:
    """Legacy AIO-only shim. Kept so the existing POST /episodes
    handler doesn't need to thread provider_id through every call site
    on day one — the new /providers/{provider}/episodes handler is the
    place where multi-show callers land."""
    return episode_path_for("aio", str(episode_id), title, album)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
