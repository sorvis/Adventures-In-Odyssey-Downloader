# odyssey-archive

Small FastAPI service that the Android app pushes new Adventures in Odyssey
episodes to and pulls them back from. Single source of truth for what's
archived. Bearer-token auth, LAN-only.

## Endpoints

All require `Authorization: Bearer <token>` except `/healthz`.

| Method | Path                              | Purpose                          |
|--------|-----------------------------------|----------------------------------|
| GET    | `/healthz`                        | liveness                         |
| POST   | `/episodes`                       | upload (multipart) — idempotent on `episode_id` |
| GET    | `/episodes?album=&q=&since=&limit=&offset=` | search/list                |
| GET    | `/episodes/{id}`                  | metadata                          |
| GET    | `/episodes/{id}/audio`            | streamed download, supports `Range` |
| GET    | `/albums`                         | list of (album, episode_count)   |
| GET    | `/albums/{name}/episodes`         | episodes in an album              |

## Layout on disk

```
/data/
  episodes.db                          # SQLite index (single source of truth)
  audio/
    <album-slug>/
      <episode-id>-<title-slug>.mp3
    unsorted/
      ...
```

## Quick start (any host with Docker)

```bash
./archive-service/scripts/up.sh
```

That's it. The script generates a token if `.env` doesn't exist,
creates `./_data` if it doesn't exist, runs `docker compose up -d --build`,
waits for `/healthz`, and prints the URL + token to paste into the
Android app's Settings → Backup.

Stop with `docker compose down`.

## Deploy on Proxmox LXC + Synology NAS

1. **Mount the Synology share on the Proxmox host** (NFS preferred for LXC bind-mounts):
   ```bash
   mkdir -p /mnt/synology/odyssey
   mount -t nfs 192.168.2.123:/volume1/odyssey /mnt/synology/odyssey
   # add to /etc/fstab to make it persistent
   ```

2. **Create the LXC** (Debian 12, unprivileged is fine; allocate 2 GB RAM, 4 GB disk):
   ```bash
   pct create 121 local:vztmpl/debian-12-standard_*.tar.zst \
     --hostname odyssey-archive --net0 name=eth0,bridge=vmbr0,ip=dhcp \
     --rootfs local-lvm:4 --memory 2048 --cores 2
   ```

3. **Bind-mount the NAS path into the LXC**:
   ```bash
   pct set 121 -mp0 /mnt/synology/odyssey,mp=/data
   pct start 121
   ```

4. **Inside the LXC**: install Docker + compose plugin, clone this repo, then:
   ```bash
   ODYSSEY_DATA_HOST_DIR=/data ./archive-service/scripts/up.sh
   ```

   The env var swaps the volume mount from the dev default (`./_data`)
   to the NAS bind-mount path (`/data`). Everything else is the same as
   the local-dev flow.

5. **Save the token** — `up.sh` prints it on success. Paste into the Android
   app's Settings → Backup URL/token.

## Importing an existing pile of MP3s

### Drop folder (server-side, recommended)

For files already in messy filenames sitting on the same machine as
the service (e.g., the old C# downloader's output, an external
drive plugged into the LXC):

```bash
# 1. Drop arbitrary mp3s into /data/import/ on the LXC.
#    SCP, NFS, USB rsync — whatever. Filenames can be any shape.
scp ~/old-aio/*.mp3 root@odyssey-archive:/data/import/

# 2. Trigger the importer.
ssh root@odyssey-archive 'cd /opt/archive-service && scripts/run-import.sh'
```

What it does:

1. Walks `/data/import/` for `*.mp3` and `*.m4a`.
2. Derives a title for each file: ID3 v2 `TIT2` first, filename
   heuristics second (`<id>-Title.mp3`, `Title (1234).mp3`, bare
   `Title.mp3`).
3. Looks the title up in the AIO catalog baked into the image.
   Match → moves the file to
   `/data/audio/<album-slug>/<id>-<title-slug>.mp3` and inserts an
   `episodes` row with the canonical album + title + broadcast
   number. Episode IDs come from the catalog's `#NNN` (e.g. 657 for
   "Clutter"); titles without a number get a hash-based synthetic id.
4. No match → moves the file to `/data/import/_unmatched/` for you
   to rename and re-drop.

Idempotent: re-running is safe (files already in `_unmatched/` are
skipped; matched re-drops `INSERT OR REPLACE` the row).

The catalog is shipped in the Docker image (`/srv/aio_catalog.json`)
sourced from `android/app/src/main/assets/aio_catalog.json`. Refresh
both copies when re-running `scripts/aio-scrape-catalog.py`.

### Pushing from a different host (HTTP)

If the MP3s live on a machine that isn't the LXC, use the client-
side script that POSTs over HTTP:

```bash
archive-service/scripts/import-audio-dir.py \
  --dir /path/to/old/episodes \
  --base-url http://odyssey-archive:8088 \
  --token "$(grep ODYSSEY_AUTH_TOKEN archive-service/.env | cut -d= -f2-)"
```

Walks recursively, same filename + ID3 parsing as the drop folder.
Idempotent — re-runs are safe. Doesn't run the catalog matcher
(album resolution happens server-side from form fields the script
sends), so files end up in `unsorted/` unless you pass `--album` per
batch.

## Development (locally, no Docker)

```bash
python3 -m venv --copies .venv
.venv/bin/pip install -r requirements.txt
ODYSSEY_AUTH_TOKEN=devtoken ODYSSEY_DATA_DIR=$PWD/_data \
  .venv/bin/uvicorn app.main:app --reload --port 8088
```

## Tests

```bash
.venv/bin/pip install -r requirements.txt    # includes pytest
.venv/bin/python -m pytest tests/ -q
```

Tests run in-process (no Docker). They cover healthz, the auth gate,
upload/list/get/audio/range, idempotency, and the album endpoints.
