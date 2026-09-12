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
   mount -t nfs nas.lan:/volume1/odyssey /mnt/synology/odyssey
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

## Diagnostics

### Is the archive keeping up with oneplace?

`scripts/check_archive_freshness.py` reports AIO episodes oneplace.com
has aired that never reached the NAS — a hole somewhere in
`oneplace → Android app → archive-service`. It's **read-only** (reports
only; never re-ingests). Re-broadcasts are de-aliased through
`aio_catalog.json` (oneplace assigns a fresh episodeId on every re-air,
but the app files it under the canonical AIO catalog #), so genuine
back-catalog gaps surface without every re-broadcast flagging a false
positive.

```bash
# Wrapper — resolves the NAS bearer token from $ODYSSEY_NAS_TOKEN,
# ~/.aio-archive-token, or the LXC's .env via the Proxmox host.
archive-service/scripts/check-freshness.sh            # table report
archive-service/scripts/check-freshness.sh --json     # machine-readable
archive-service/scripts/check-freshness.sh --probe-window 80

# Or call the checker directly with explicit creds:
archive-service/scripts/check_archive_freshness.py \
  --nas-url http://<lxc-ip>:8088 --nas-token "$TOKEN"
```

Exit code: `0` fully archived · `1` at least one gap · `2` transport/
credential error. Overrides (env): `ODYSSEY_NAS_URL`,
`ODYSSEY_NAS_TOKEN`, `PROXMOX_HOST`, `LXC_ID`.

### Fixing mis-titled archived episodes

`scripts/whisper_titles.py` transcribes each episode's tail (AIO) or
head (YSH) on the CT 112 GPU via whisperx, fuzzy-matches the spoken
title against `aio_catalog.json`, and proposes corrections. `validate`
writes a report (and stamps `title_validated_at` server-side); `plan`
previews proposals from that report without writing; `apply` PATCHes
the titles. See the script header for the full flow.

```bash
export ODYSSEY_BASE_URL=http://<lxc-ip>:8088
export ODYSSEY_AUTH_TOKEN="$TOKEN"
scripts/whisper_titles.py validate --limit 20 --out /tmp/report.json
scripts/whisper_titles.py plan     --report /tmp/report.json
scripts/whisper_titles.py apply    --report /tmp/report.json --threshold 0.95
```

### Checking audio files for truncation

`scripts/check-integrity.sh` finds episodes whose audio stops partway
through — a download that died mid-transfer and got archived as though
it were complete. Those files play fine and look fine in the app; they
just end in the middle of the story.

The test is one each file makes against itself. Every MP3 in the
archive carries a Xing/Info header declaring how many bytes the
complete file should contain, so comparing that against the real size
finds a short file immediately. It reads only the first ~16 KB of each
episode, so a full sweep of 462 episodes is a few hundred small range
requests — not ~10 GB of downloads — and needs no ffmpeg anywhere.

Three checks ride along: a duration-outlier pass (catches a file whose
header was itself written against truncated input, so it agrees with
itself), a DB-size-vs-disk-size comparison (catches a file that lost
bytes after archiving), and `--deep`, which re-downloads everything to
verify stored sha256s and so catches corruption that preserves length.

```bash
archive-service/scripts/check-integrity.sh                 # full sweep
archive-service/scripts/check-integrity.sh --provider ysh  # one show
archive-service/scripts/check-integrity.sh --deep          # + sha256 verify
```

Read-only, and exits 1 when anything needs attention, so it works as a
cron check. A handful of older files carry no Xing/Info header; those
fall back to a duration derived from their constant bitrate, and the
report says so rather than quietly treating the weaker verdict as
equivalent.

### Auditing the Your Story Hour library

`scripts/audit-ysh.sh` sweeps every YSH row and answers two questions
per episode from the audio itself:

1. **Does this actually belong to YSH?** Scored off station-ID phrases
   ("…has been Your Story Hour, from Berrien Springs") versus another
   show's branding ("Adventures in Odyssey", "Whit's End"). This catches
   a mis-ingested episode that title matching alone cannot — a foreign
   file scores 0.00 against every YSH title, which looks exactly like a
   YSH episode whisperx garbled.
2. **Does the announced title match the row?** The storyteller names
   the story in the cold-open ("I call my story, …"); that is matched
   against the full 1055-track yourstoryhour.org catalog.

It also cross-checks each row's title/album against the catalog entry
for its `ysh-sku-<id>` external_id, which needs no audio at all.

Read-only — it reports and never renames, deletes, or stamps
`title_validated_at`. Run it from the dev box (needs local ffmpeg, HTTP
to the service, and ssh to the Proxmox host owning the whisperx LXC).

```bash
archive-service/scripts/audit-ysh.sh              # full sweep
archive-service/scripts/audit-ysh.sh --quick      # catalog metadata only, no GPU
archive-service/scripts/audit-ysh.sh --limit 10   # smoke run
archive-service/scripts/audit-ysh.sh --report /tmp/ysh-audit.json   # re-read
```

A finding is a prompt to listen, not a verdict: `inconclusive` means
YSH never announced the title in the clip (common — the host often
describes the subject instead of naming the story), and only
`MISMATCH` / `FOREIGN` claim something is actually wrong. Mismatches
are deliberately hard to trigger — the rival title has to clear the
threshold, sit directly behind a real credit phrase, and beat the
stored title by a margin — because the failure mode that matters is
renaming a correctly-labeled episode.

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
