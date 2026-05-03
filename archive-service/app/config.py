import os
from pathlib import Path

DATA_DIR = Path(os.environ.get("ODYSSEY_DATA_DIR", "/data"))
DB_PATH = DATA_DIR / "episodes.db"
AUDIO_DIR = DATA_DIR / "audio"
AUTH_TOKEN = os.environ["ODYSSEY_AUTH_TOKEN"]
