from pathlib import Path
from typing import Annotated
from fastapi import APIRouter, Depends, Form, Header, HTTPException, UploadFile, File, Query
from pydantic import BaseModel
from .. import db
from ..auth import require_token
from ..storage import episode_path, sha256_file
from ..range_stream import stream_file
from ..scrape_aio import enrich_album

router = APIRouter(prefix="/episodes", dependencies=[Depends(require_token)])


class EpisodeOut(BaseModel):
    episode_id: int
    title: str
    air_date: str | None
    album: str | None
    description: str | None
    duration_secs: int | None
    file_size: int
    sha256: str | None
    archived_at: str


def _row_to_out(r) -> EpisodeOut:
    return EpisodeOut(
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


@router.post("", response_model=EpisodeOut, status_code=201)
async def create_episode(
    episode_id: Annotated[int, Form()],
    title: Annotated[str, Form()],
    air_date: Annotated[str | None, Form()] = None,
    album: Annotated[str | None, Form()] = None,
    description: Annotated[str | None, Form()] = None,
    duration_secs: Annotated[int | None, Form()] = None,
    source_url: Annotated[str | None, Form()] = None,
    audio: UploadFile = File(...),
):
    with db.connect() as c:
        existing = c.execute(
            "SELECT 1 FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
        if existing:
            row = c.execute(
                "SELECT * FROM episodes WHERE episode_id = ?", (episode_id,)
            ).fetchone()
            return _row_to_out(row)

    if not album:
        album = enrich_album(title)

    out_path = episode_path(episode_id, title, album)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = out_path.with_suffix(out_path.suffix + ".part")

    with tmp_path.open("wb") as f:
        while chunk := await audio.read(1 << 20):
            f.write(chunk)
    tmp_path.replace(out_path)

    size = out_path.stat().st_size
    sha = sha256_file(out_path)

    with db.connect() as c:
        # INSERT OR IGNORE so two clients racing to upload the same
        # episode_id don't 500 on the second one — the SELECT below
        # returns whichever row landed first. The file write that
        # ran above is overwritten in-place by the second client's
        # atomic-rename, but since both phones pull the same audio
        # bytes from the same upstream source it's a benign re-write
        # (same content, may be wasted bandwidth — acceptable).
        c.execute(
            """INSERT OR IGNORE INTO episodes
               (episode_id, title, air_date, album, description, duration_secs,
                file_path, file_size, sha256, source_url,
                provider_id, external_id)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                episode_id, title, air_date, album, description, duration_secs,
                str(out_path), size, sha, source_url,
                # Legacy POST handler — no provider_id form field, so
                # the row is AIO by definition. external_id mirrors
                # episode_id stringified so the unique index can find
                # the row alongside any multi-show ones the new POST
                # route writes (step 11b).
                "aio", str(episode_id),
            ),
        )
        row = c.execute(
            "SELECT * FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
    return _row_to_out(row)


@router.get("", response_model=list[EpisodeOut])
def list_episodes(
    album: str | None = None,
    q: str | None = None,
    since: str | None = Query(None, description="ISO date; episodes archived after"),
    limit: int = Query(50, ge=1, le=500),
    offset: int = Query(0, ge=0),
):
    where, params = [], []
    if album:
        where.append("album = ?"); params.append(album)
    if q:
        where.append("(title LIKE ? COLLATE NOCASE OR description LIKE ? COLLATE NOCASE)")
        params += [f"%{q}%", f"%{q}%"]
    if since:
        where.append("archived_at >= ?"); params.append(since)
    sql = "SELECT * FROM episodes"
    if where:
        sql += " WHERE " + " AND ".join(where)
    sql += " ORDER BY air_date DESC, episode_id DESC LIMIT ? OFFSET ?"
    params += [limit, offset]
    with db.connect() as c:
        rows = c.execute(sql, params).fetchall()
    return [_row_to_out(r) for r in rows]


@router.get("/{episode_id}", response_model=EpisodeOut)
def get_episode(episode_id: int):
    with db.connect() as c:
        row = c.execute(
            "SELECT * FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
    if not row:
        raise HTTPException(404, "not found")
    return _row_to_out(row)


@router.get("/{episode_id}/audio")
def get_audio(episode_id: int, range: str | None = Header(default=None)):
    with db.connect() as c:
        row = c.execute(
            "SELECT file_path FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
    if not row:
        raise HTTPException(404, "not found")
    return stream_file(Path(row["file_path"]), range)
