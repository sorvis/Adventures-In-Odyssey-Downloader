import os
from pathlib import Path

DATA_DIR = Path(os.environ.get("ODYSSEY_DATA_DIR", "/data"))
DB_PATH = DATA_DIR / "episodes.db"
AUDIO_DIR = DATA_DIR / "audio"
# Drop folder for the import_dropbox flow. User SCPs/NFS-copies
# arbitrary mp3s into here; running scripts/run-import.sh moves them
# into the right /data/audio/<album-slug>/ folder. The _unmatched/
# subdirectory holds files whose titles don't match the AIO catalog.
IMPORT_DIR = DATA_DIR / "import"
IMPORT_UNMATCHED_DIR = IMPORT_DIR / "_unmatched"
# Catalog asset baked into the image at build time. Refresh by
# re-running scripts/aio-scrape-catalog.py and re-building.
CATALOG_PATH = Path("/srv/aio_catalog.json")
AUTH_TOKEN = os.environ["ODYSSEY_AUTH_TOKEN"]
