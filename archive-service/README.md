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

4. **Inside the LXC**: install docker + compose, clone this repo, then:
   ```bash
   cd archive-service
   cp .env.example .env
   # generate a token:
   openssl rand -hex 32 | sed -i "s/replace-me.*/$(cat)/" .env   # or paste manually
   docker compose up -d --build
   curl http://127.0.0.1:8088/healthz
   ```

5. **Save the token** — paste it into the Android app's Settings → NAS URL/token.

## Development (locally, no Docker)

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
export ODYSSEY_AUTH_TOKEN=devtoken ODYSSEY_DATA_DIR=$PWD/_data
uvicorn app.main:app --reload --port 8088
```
