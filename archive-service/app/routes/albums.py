from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel
from .. import db
from ..auth import require_token
from .episodes import EpisodeOut, _row_to_out

router = APIRouter(prefix="/albums", dependencies=[Depends(require_token)])


class AlbumOut(BaseModel):
    name: str
    episode_count: int


@router.get("", response_model=list[AlbumOut])
def list_albums():
    with db.connect() as c:
        rows = c.execute(
            """SELECT COALESCE(album, 'Unsorted') AS name, COUNT(*) AS n
               FROM episodes GROUP BY name ORDER BY name COLLATE NOCASE"""
        ).fetchall()
    return [AlbumOut(name=r["name"], episode_count=r["n"]) for r in rows]


@router.get("/{name}/episodes", response_model=list[EpisodeOut])
def album_episodes(name: str):
    sql = "SELECT * FROM episodes WHERE "
    if name == "Unsorted":
        sql += "album IS NULL OR album = ''"
        params: tuple = ()
    else:
        sql += "album = ?"
        params = (name,)
    sql += " ORDER BY air_date DESC, episode_id DESC"
    with db.connect() as c:
        rows = c.execute(sql, params).fetchall()
    if not rows:
        raise HTTPException(404, "no episodes for that album")
    return [_row_to_out(r) for r in rows]
