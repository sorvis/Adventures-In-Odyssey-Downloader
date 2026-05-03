# Adventures in Odyssey — downloader + archive

Two-part rewrite of the original C# CLI downloader (preserved at the repo root
as a reference implementation):

| Component         | Where                | What it is                                    |
|-------------------|----------------------|-----------------------------------------------|
| Android app       | [`android/`](android/)              | Daily check, download, in-app player          |
| NAS archive       | [`archive-service/`](archive-service/) | FastAPI service, Docker on Proxmox LXC        |

## How it fits together

```
oneplace.com  ─── /api/related-episodes ──►  Android app  ──── POST /episodes ──►  archive-service (LXC)
                                                  │                                       │
                                                  │                                       ├── episodes.db (SQLite)
                                                  │                                       └── audio/<album>/<id>-<slug>.mp3   (on Synology NFS mount)
                                                  │
                                                  └─ in-app player ─────── pulls back ───►  GET /episodes/{id}/audio (range)
```

The Android app **works fully without the NAS** — daily download, play, and
retention all run standalone. NAS features (browse, search, archive push,
pull-from-NAS) hide gracefully when no NAS is configured.

## Quick start

1. **Stand up the NAS service.** See [archive-service/README.md](archive-service/README.md) — runs in a Proxmox LXC with the Synology share bind-mounted at `/data`. Generate a bearer token with `openssl rand -hex 32`.
2. **Open `android/` in Android Studio** and install on your phone.
3. **In the app's Settings**, paste the NAS URL (`http://<lxc-ip>:8088`) and the bearer token. Or leave both blank to run standalone.

## Reference impl

The original `*.cs` files at the repo root are the upstream
sorvis/Adventures-In-Odyssey-Downloader CLI. They are kept as **reference for
the scraping logic** but should not be modified — oneplace.com no longer
serves the HTML the C# scraper expects, and the new app uses the JSON API at
`/api/related-episodes` instead.
