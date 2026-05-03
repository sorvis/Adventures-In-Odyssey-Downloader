from fastapi import FastAPI
from . import db
from .routes import episodes, albums

app = FastAPI(title="odyssey-archive", version="0.1.0")


@app.on_event("startup")
def _startup() -> None:
    db.init()


@app.get("/healthz")
def healthz():
    return {"ok": True}


app.include_router(episodes.router)
app.include_router(albums.router)
