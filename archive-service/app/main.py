from contextlib import asynccontextmanager

from fastapi import FastAPI

from . import db
from .migrate_layout import migrate_layout
from .routes import episodes, albums


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Startup: create the data dir + tables. (Was previously
    # @app.on_event("startup") which is deprecated and — more
    # importantly — doesn't fire under FastAPI TestClient unless the
    # client is used as a context manager, so pytest saw an
    # uninitialized DB and every test failed with sqlite3.OperationalError.)
    db.init()
    # Move legacy AIO downloads under audio/aio/ so YSH content can
    # sit alongside under audio/ysh/. Idempotent + sentinel-gated; runs
    # once per (DATA_DIR, deployed image) pair.
    migrate_layout()
    yield
    # Shutdown: nothing to clean up — sqlite connections are per-request.


app = FastAPI(title="odyssey-archive", version="0.1.0", lifespan=lifespan)


@app.get("/healthz")
def healthz():
    return {"ok": True}


app.include_router(episodes.router)
app.include_router(albums.router)
