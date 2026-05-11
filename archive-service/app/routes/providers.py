"""
Provider-scoped resource paths — `/providers/{provider}/episodes/...`
and `/providers/{provider}/albums`. Canonical surface for multi-show
clients (YSH-aware Android, future shows).

The legacy `/episodes` + `/albums` routes (routes/episodes.py +
routes/albums.py) stay intact for v0.1.37-and-older Android clients
that don't know about provider paths — they default to AIO on the
read side and tag inserts as `provider_id='aio'`. Two routers, one
shared DB, no behavioral collisions.
"""
from __future__ import annotations

from pathlib import Path
from typing import Annotated

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Query, UploadFile
from pydantic import BaseModel

from .. import db
from ..auth import require_token
from ..range_stream import stream_file
from ..scrape_aio import enrich_album
from ..storage import episode_path_for, sha256_file


router = APIRouter(prefix="/providers/{provider}", dependencies=[Depends(require_token)])


class EpisodeOutV2(BaseModel):
    """Multi-show response shape. Differs from legacy `EpisodeOut` in
    two ways:
      - `external_id` is the canonical row identity (required).
      - `episode_id` becomes optional — YSH rows leave it null
        because they don't have a legacy oneplace CMS integer id.
    """
    provider_id: str
    external_id: str
    episode_id: int | None = None
    title: str
    air_date: str | None
    album: str | None
    description: str | None
    duration_secs: int | None
    file_size: int
    sha256: str | None
    archived_at: str


def _row_to_outv2(r) -> EpisodeOutV2:
    return EpisodeOutV2(
        provider_id=r["provider_id"],
        external_id=r["external_id"],
        episode_id=r["episode_id"],
        title=r["title"],
        air_date=r["air_date"],
        album=r["album"],
        description=r["description"],
        duration_secs=r["duration_secs"],
        file_size=r["file_size"],
        sha256=r["sha256"],
        archived_at=r["archived_at"],
    )


@router.post("/episodes", response_model=EpisodeOutV2, status_code=201)
async def create_episode(
    provider: str,
    external_id: Annotated[str, Form()],
    title: Annotated[str, Form()],
    air_date: Annotated[str | None, Form()] = None,
    album: Annotated[str | None, Form()] = None,
    description: Annotated[str | None, Form()] = None,
    duration_secs: Annotated[int | None, Form()] = None,
    source_url: Annotated[str | None, Form()] = None,
    audio: UploadFile = File(...),
):
    """Provider-aware upload. AIO clients can use either this OR the
    legacy POST /episodes — both end up writing the same row shape
    (provider_id + external_id + episode_id all populated for AIO).
    YSH clients use this exclusively; episode_id stays NULL since
    sku_id is the canonical id."""
    # Dedup by (provider, external_id). Same idempotency contract as
    # the legacy POST: a second upload of an existing row returns the
    # existing row instead of erroring.
    with db.connect() as c:
        existing = c.execute(
            "SELECT * FROM episodes WHERE provider_id = ? AND external_id = ?",
            (provider, external_id),
        ).fetchone()
        if existing:
            return _row_to_outv2(existing)

    # AIO clients can opt in to server-side album enrichment by
    # omitting the album field — the catalog scraper fills it in.
    # YSH clients always send album directly (they have richer
    # metadata than the AIO catalog) and skip enrichment.
    if not album and provider == "aio":
        album = enrich_album(title)

    out_path = episode_path_for(provider, external_id, title, album)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = out_path.with_suffix(out_path.suffix + ".part")

    with tmp_path.open("wb") as f:
        while chunk := await audio.read(1 << 20):
            f.write(chunk)
    tmp_path.replace(out_path)

    size = out_path.stat().st_size
    sha = sha256_file(out_path)

    # For AIO clients we still populate episode_id (the legacy integer
    # column has a UNIQUE PRIMARY KEY constraint, so for AIO it must
    # match the external_id parsed as int). YSH rows leave it NULL —
    # SQLite allows NULL in INTEGER PRIMARY KEY only when the value is
    # absent from the INSERT, so we use a NULL placeholder there.
    aio_episode_id: int | None = (
        int(external_id) if provider == "aio" and external_id.isdigit() else None
    )
    with db.connect() as c:
        # INSERT OR IGNORE — same race-handling pattern as the legacy
        # handler. If a parallel upload landed first the SELECT
        # returns its row, and the second client's file write is a
        # benign re-write (same bytes from same upstream).
        if aio_episode_id is not None:
            c.execute(
                """INSERT OR IGNORE INTO episodes
                   (episode_id, provider_id, external_id, title, air_date,
                    album, description, duration_secs, file_path, file_size,
                    sha256, source_url)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    aio_episode_id, provider, external_id, title, air_date,
                    album, description, duration_secs,
                    str(out_path), size, sha, source_url,
                ),
            )
        else:
            # YSH (or any provider with non-numeric external_ids):
            # let SQLite assign a rowid for episode_id since the
            # column is INTEGER PRIMARY KEY (autoincrement). The
            # external_id is the canonical identity for these rows.
            c.execute(
                """INSERT OR IGNORE INTO episodes
                   (provider_id, external_id, title, air_date,
                    album, description, duration_secs, file_path, file_size,
                    sha256, source_url)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                (
                    provider, external_id, title, air_date,
                    album, description, duration_secs,
                    str(out_path), size, sha, source_url,
                ),
            )
        row = c.execute(
            "SELECT * FROM episodes WHERE provider_id = ? AND external_id = ?",
            (provider, external_id),
        ).fetchone()
    return _row_to_outv2(row)


@router.get("/episodes", response_model=list[EpisodeOutV2])
def list_episodes(
    provider: str,
    album: str | None = None,
    q: str | None = None,
    since: str | None = Query(None, description="ISO date; episodes archived after"),
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    where = ["provider_id = ?"]
    params: list = [provider]
    if album:
        where.append("album = ?"); params.append(album)
    if q:
        where.append("(title LIKE ? COLLATE NOCASE OR description LIKE ? COLLATE NOCASE)")
        params += [f"%{q}%", f"%{q}%"]
    if since:
        where.append("archived_at >= ?"); params.append(since)
    sql = (
        "SELECT * FROM episodes WHERE " + " AND ".join(where)
        + " ORDER BY air_date DESC, external_id DESC LIMIT ? OFFSET ?"
    )
    params += [limit, offset]
    with db.connect() as c:
        rows = c.execute(sql, params).fetchall()
    return [_row_to_outv2(r) for r in rows]


@router.get("/episodes/{external_id}", response_model=EpisodeOutV2)
def get_episode(provider: str, external_id: str):
    with db.connect() as c:
        row = c.execute(
            "SELECT * FROM episodes WHERE provider_id = ? AND external_id = ?",
            (provider, external_id),
        ).fetchone()
    if not row:
        raise HTTPException(404, "not found")
    return _row_to_outv2(row)


@router.get("/episodes/{external_id}/audio")
def get_audio(provider: str, external_id: str, range: str | None = Header(default=None)):
    with db.connect() as c:
        row = c.execute(
            "SELECT file_path FROM episodes WHERE provider_id = ? AND external_id = ?",
            (provider, external_id),
        ).fetchone()
    if not row:
        raise HTTPException(404, "not found")
    return stream_file(Path(row["file_path"]), range)


class AlbumOutV2(BaseModel):
    name: str
    episode_count: int


@router.get("/albums", response_model=list[AlbumOutV2])
def list_albums(provider: str):
    with db.connect() as c:
        rows = c.execute(
            """SELECT COALESCE(album, 'Unsorted') AS name, COUNT(*) AS n
                 FROM episodes WHERE provider_id = ? GROUP BY name
             ORDER BY name COLLATE NOCASE""",
            (provider,),
        ).fetchall()
    return [AlbumOutV2(name=r["name"], episode_count=r["n"]) for r in rows]


@router.get("/albums/{name}/episodes", response_model=list[EpisodeOutV2])
def album_episodes(provider: str, name: str):
    sql = "SELECT * FROM episodes WHERE provider_id = ? AND "
    params: list = [provider]
    if name == "Unsorted":
        sql += "(album IS NULL OR album = '')"
    else:
        sql += "album = ?"
        params.append(name)
    sql += " ORDER BY air_date DESC, external_id DESC"
    with db.connect() as c:
        rows = c.execute(sql, params).fetchall()
    if not rows:
        raise HTTPException(404, "no episodes for that album")
    return [_row_to_outv2(r) for r in rows]
