import os
import re
from pathlib import Path
from typing import Iterator
from fastapi import Header, HTTPException
from fastapi.responses import StreamingResponse, Response

_RANGE_RE = re.compile(r"bytes=(\d*)-(\d*)")
_CHUNK = 1 << 16  # 64 KiB


def stream_file(path: Path, range_header: str | None) -> Response:
    if not path.exists():
        raise HTTPException(404, "audio file missing on disk")
    size = path.stat().st_size

    if not range_header:
        return StreamingResponse(
            _iter_range(path, 0, size - 1),
            media_type="audio/mpeg",
            headers={"Accept-Ranges": "bytes", "Content-Length": str(size)},
        )

    m = _RANGE_RE.match(range_header)
    if not m:
        raise HTTPException(416, "invalid Range header")
    start_s, end_s = m.groups()
    start = int(start_s) if start_s else 0
    end = int(end_s) if end_s else size - 1
    if start > end or end >= size:
        raise HTTPException(
            416, "range not satisfiable", headers={"Content-Range": f"bytes */{size}"}
        )
    length = end - start + 1
    return StreamingResponse(
        _iter_range(path, start, end),
        status_code=206,
        media_type="audio/mpeg",
        headers={
            "Accept-Ranges": "bytes",
            "Content-Range": f"bytes {start}-{end}/{size}",
            "Content-Length": str(length),
        },
    )


def _iter_range(path: Path, start: int, end: int) -> Iterator[bytes]:
    remaining = end - start + 1
    with path.open("rb") as f:
        f.seek(start)
        while remaining > 0:
            chunk = f.read(min(_CHUNK, remaining))
            if not chunk:
                break
            remaining -= len(chunk)
            yield chunk
