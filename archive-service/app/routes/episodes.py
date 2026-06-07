from pathlib import Path
from typing import Annotated
from fastapi import APIRouter, Depends, Form, Header, HTTPException, Response, UploadFile, File, Query
from pydantic import BaseModel
from .. import db
from ..auth import require_token
from ..storage import episode_path, sha256_file
from ..range_stream import stream_file
from ..scrape_aio import enrich_album

router = APIRouter(prefix="/episodes", dependencies=[Depends(require_token)])


class EpisodePatch(BaseModel):
    """PATCH body — title and/or album. Both optional; at least one
    must be present or the call 400s. Used by the whisper-title
    validation pipeline (scripts/whisper_titles.py) to commit
    title corrections derived from end-of-episode announcer credits.
    """
    title: str | None = None
    album: str | None = None


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
    title_validated_at: str | None = None
    # Surfaced so scripts/whisper_titles.py can dispatch by provider:
    # AIO announces the title in the closing seconds, YSH at the start.
    provider_id: str | None = None
    external_id: str | None = None


def _row_to_out(r) -> EpisodeOut:
    # Older sqlite Row objects from pre-migration DBs don't expose the
    # new column via __getitem__; fall back to None.
    try:
        validated = r["title_validated_at"]
    except (IndexError, KeyError):
        validated = None
    try:
        provider = r["provider_id"]
    except (IndexError, KeyError):
        provider = None
    try:
        external = r["external_id"]
    except (IndexError, KeyError):
        external = None
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
        title_validated_at=validated,
        provider_id=provider,
        external_id=external,
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


@router.head("/{episode_id}")
def head_episode(episode_id: int) -> Response:
    """Verify-before-prune endpoint for the Android RetentionWorker.

    Returns 200 only when the DB row AND the on-disk file are present.
    A row pointing at a deleted file returns 410 Gone so the client can
    distinguish "definitively missing" (and re-archive) from "row never
    existed" (404, also a definitive no). Anything else (network error,
    5xx) is the client's signal to leave the local copy alone.

    Cheap: no body, no streaming, just a SELECT + stat().
    """
    with db.connect() as c:
        row = c.execute(
            "SELECT file_path, file_size FROM episodes WHERE episode_id = ?",
            (episode_id,),
        ).fetchone()
    if not row:
        raise HTTPException(404, "row not in archive index")
    path = Path(row["file_path"])
    if not path.exists():
        raise HTTPException(410, "row present but audio file missing")
    return Response(
        status_code=200,
        headers={"X-File-Size": str(row["file_size"])},
    )


@router.get("/{episode_id}/audio")
def get_audio(episode_id: int, range: str | None = Header(default=None)):
    with db.connect() as c:
        row = c.execute(
            "SELECT file_path FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
    if not row:
        raise HTTPException(404, "not found")
    return stream_file(Path(row["file_path"]), range)


@router.patch("/{episode_id}", response_model=EpisodeOut)
def patch_episode(episode_id: int, body: EpisodePatch):
    if body.title is None and body.album is None:
        raise HTTPException(400, "PATCH body must include title and/or album")
    with db.connect() as c:
        row = c.execute(
            "SELECT * FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
        if not row:
            raise HTTPException(404, "not found")

        new_title = body.title if body.title is not None else row["title"]
        new_album = body.album if body.album is not None else row["album"]

        # When the title (or album) changes we relocate the audio file
        # to the canonical path under the new naming. episode_path is
        # the same helper the create flow uses, so the layout stays
        # consistent across import vs. correction paths. We rename
        # in-place ONLY when the canonical path actually differs —
        # avoids a no-op rename round-trip when the caller PATCHes
        # the album alone but title→path is unchanged.
        old_path = Path(row["file_path"])
        new_path = episode_path(episode_id, new_title, new_album)
        moved = False
        if new_path != old_path and old_path.exists():
            new_path.parent.mkdir(parents=True, exist_ok=True)
            old_path.replace(new_path)
            moved = True

        # PATCH lands a title/album change → that change came from a
        # human or from the whisper-titles pipeline. Either way we
        # stamp title_validated_at so subsequent validate runs can
        # skip this row.
        c.execute(
            "UPDATE episodes SET title = ?, album = ?, file_path = ?, "
            "title_validated_at = datetime('now') WHERE episode_id = ?",
            (new_title, new_album, str(new_path) if moved else row["file_path"], episode_id),
        )
        updated = c.execute(
            "SELECT * FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
    return _row_to_out(updated)


@router.put("/{episode_id}/title-validated", response_model=EpisodeOut)
def mark_title_validated(episode_id: int):
    """Stamp `title_validated_at = now` without touching title/album.
    Called by scripts/whisper_titles.py for every episode whose tail
    transcription succeeded, regardless of whether the catalog match
    proposed a change — so a re-run can skip already-verified rows.
    Idempotent: re-stamping refreshes the timestamp.
    """
    with db.connect() as c:
        row = c.execute(
            "SELECT 1 FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
        if not row:
            raise HTTPException(404, "not found")
        c.execute(
            "UPDATE episodes SET title_validated_at = datetime('now') "
            "WHERE episode_id = ?",
            (episode_id,),
        )
        updated = c.execute(
            "SELECT * FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
    return _row_to_out(updated)


@router.delete("/{episode_id}", status_code=204)
def delete_episode(episode_id: int):
    """Drop the DB row AND its on-disk audio. Used by the dedup pass
    in scripts/whisper_titles.py — when whisper transcription confirms
    that two episode_ids point at the same recording, the worse copy
    (older / smaller / unverified) is removed via this endpoint.
    Idempotent: 404 only if the row is genuinely absent; a row whose
    audio was already gone still succeeds (file deletion is best-effort).
    """
    with db.connect() as c:
        row = c.execute(
            "SELECT file_path FROM episodes WHERE episode_id = ?", (episode_id,)
        ).fetchone()
        if not row:
            raise HTTPException(404, "not found")
        Path(row["file_path"]).unlink(missing_ok=True)
        c.execute("DELETE FROM episodes WHERE episode_id = ?", (episode_id,))
    return Response(status_code=204)
