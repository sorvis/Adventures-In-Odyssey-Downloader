#!/usr/bin/env python3
"""Generate Adventures-In-Odyssey-Downloader launcher-icon candidates
via the homelab image-api at http://192.168.2.143:8000.

Writes ~12 PNG candidates spanning themes into tmp_icons/, builds a
small HTML gallery (tmp_icons/index.html), and optionally pushes
both to the shared mockup host at http://192.168.2.146/odyssey-icons/.

Once you pick one, copy it into android/app/src/main/res/ as the
foreground layer of an adaptive icon (mipmap-anydpi-v26/ic_launcher.xml
+ mipmap-*/ic_launcher_foreground.png at the right densities).

Usage:
    python3 scripts/gen-icons.py             # generate + write gallery locally
    python3 scripts/gen-icons.py --deploy    # also push to mockup host
"""
from __future__ import annotations
import argparse
import concurrent.futures as cf
import json
import pathlib
import shutil
import subprocess
import sys
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "tmp_icons"
OUT.mkdir(parents=True, exist_ok=True)
API = "http://192.168.2.143:8000/generate"

# Each candidate is (filename, human label, prompt). Group label →
# rendered as a section header in the gallery so the user can compare
# styles side-by-side without playing find-the-pattern.
CANDIDATES = [
    # ---- Radio mic theme ----
    ("retro_mic_teal.png", "Retro mic — teal/gold",
     "vintage radio microphone on stand, sound waves radiating, flat icon design, "
     "vibrant teal and gold, centered on solid background, app icon, rounded square, "
     "no text, no letters"),
    ("retro_mic_navy.png", "Retro mic — navy/cream",
     "vintage broadcast microphone with metal grille, flat icon, navy blue background, "
     "warm cream and copper accents, app icon design, no text, no letters"),
    ("retro_mic_minimal.png", "Retro mic — minimal line",
     "minimalist line-art microphone icon, single colored stroke on flat background, "
     "modern flat ui style, app icon, no text, no letters"),

    # ---- Adventure / compass theme ----
    ("compass_headphones.png", "Compass + headphones",
     "compass and headphones combined into a single emblem, adventure podcast theme, "
     "flat illustration, navy blue and orange, app icon, centered composition, no text"),
    ("compass_minimal.png", "Compass — minimal",
     "minimalist compass star icon, geometric flat design, deep navy with gold north "
     "needle, app icon, no text, no letters"),
    ("mountain_radio.png", "Mountain + radio waves",
     "stylized mountain peak with radio signal waves arcing over it, flat illustration, "
     "sunset orange and deep blue, app icon, no text"),

    # ---- Audio / playback theme ----
    ("podcast_play.png", "Play button — waveform",
     "stylized play button with audio waveform radiating outward, modern flat icon, "
     "purple and amber gradient, app icon, no text, no letters"),
    ("waveform_arc.png", "Waveform — arc",
     "abstract audio waveform arcing across the frame, flat geometric design, "
     "deep blue and warm yellow, app icon, no text, no letters"),
    ("cassette_mountain.png", "Cassette + mountain",
     "audio cassette tape with mountain peaks behind it, flat illustration, "
     "warm sunset colors, retro adventure vibe, app icon, no text, no letters"),

    # ---- Storybook / radio drama theme (AIO/YSH connect) ----
    ("storybook_radio.png", "Storybook + radio",
     "open book with radio waves coming out of the pages, warm storytelling theme, "
     "flat illustration, amber and forest green, app icon, no text"),
    ("vintage_radio.png", "Vintage tabletop radio",
     "vintage tabletop tube radio in three-quarter view, flat illustration, warm wood "
     "tones with cream face, app icon, retro feel, no text"),
    ("starry_listen.png", "Starry sky + listening figure",
     "small silhouette listening to headphones under a starry night sky, flat "
     "illustration, deep blue with golden stars, app icon, dreamy, no text"),
]


def fetch_one(filename: str, label: str, prompt: str) -> tuple[str, str, int | None, str | None]:
    """Generate one icon. Returns (filename, label, byte_count, error)."""
    body = json.dumps({
        "prompt": prompt,
        "size": 1024,
        "style": "icon_sdxl_turbo",
        "negative_prompt": "text, letters, words, watermark, signature, blurry, low-quality",
    }).encode()
    req = urllib.request.Request(
        API, data=body, method="POST", headers={"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as r:
            data = r.read()
    except Exception as e:
        return (filename, label, None, str(e))
    (OUT / filename).write_bytes(data)
    return (filename, label, len(data), None)


def write_gallery(results: list[tuple[str, str, int | None, str | None]]) -> pathlib.Path:
    """Build a side-by-side HTML gallery for visual comparison."""
    cards: list[str] = []
    for filename, label, size, err in results:
        if err:
            cards.append(
                f"""<div class="card error">
  <div class="img-wrap"><div class="err">FAILED</div></div>
  <h3>{label}</h3>
  <p class="meta">{filename}</p>
  <p class="err-msg">{err}</p>
</div>"""
            )
        else:
            kb = (size or 0) // 1024
            cards.append(
                f"""<div class="card">
  <div class="img-wrap"><img src="{filename}" alt="{label}"></div>
  <h3>{label}</h3>
  <p class="meta">{filename} · {kb} KB</p>
</div>"""
            )
    body = "\n".join(cards)
    html = f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Odyssey launcher-icon candidates</title>
<style>
  :root {{ --bg:#0f1115; --fg:#e8eaef; --muted:#9aa1ab; --card:#1a1d24; --accent:#7aa2ff; --err:#ff6b6b; }}
  body {{ margin:0; font:14px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;
         background:var(--bg); color:var(--fg); padding:24px; }}
  h1 {{ margin:0 0 8px; }}
  .lead {{ color:var(--muted); margin:0 0 24px; }}
  .grid {{ display:grid; grid-template-columns:repeat(auto-fill,minmax(260px,1fr)); gap:16px; }}
  .card {{ background:var(--card); padding:12px; border-radius:10px; border:1px solid #2a2e36; }}
  .img-wrap {{ aspect-ratio:1/1; background:#0a0c10; border-radius:6px; overflow:hidden;
               display:flex; align-items:center; justify-content:center; }}
  .img-wrap img {{ width:100%; height:100%; object-fit:contain; }}
  .card h3 {{ margin:10px 0 2px; font-size:14px; }}
  .meta {{ margin:0; color:var(--muted); font-size:12px; }}
  .card.error .img-wrap {{ background:#2a1414; }}
  .err {{ color:var(--err); font-weight:bold; }}
  .err-msg {{ color:var(--err); font-size:12px; word-break:break-all; }}
  .hint {{ background:#1a1d24; padding:12px 16px; border-left:3px solid var(--accent);
           border-radius:0 6px 6px 0; margin:24px 0; }}
  code {{ background:#2a2e36; padding:1px 6px; border-radius:3px; }}
</style>
</head>
<body>
  <h1>Odyssey launcher-icon candidates</h1>
  <p class="lead">{len([r for r in results if r[3] is None])} of {len(results)} generated via SDXL-Turbo. Adaptive icons crop to a circle/squircle/etc. on most launchers — viable candidates should keep the subject centered and away from the edges.</p>
  <div class="hint">Once you pick a winner, copy <code>tmp_icons/&lt;name&gt;.png</code> into <code>android/app/src/main/res/mipmap-*/ic_launcher_foreground.png</code> at each density, and re-author <code>mipmap-anydpi-v26/ic_launcher.xml</code> if needed.</div>
  <div class="grid">
{body}
  </div>
</body>
</html>
"""
    p = OUT / "index.html"
    p.write_text(html)
    return p


def deploy_to_mockup_host() -> None:
    """Push tmp_icons/ to the shared mockup host at /var/www/mockups/odyssey-icons/.
    Mirrors the pattern in android-app-template/mockup/deploy/deploy.sh."""
    SLUG = "odyssey-icons"
    PVE_HOST = "192.168.2.123"
    CTID = "118"
    MOCKUPS_IP = "192.168.2.146"

    print(f"\n==> Deploying {len(list(OUT.glob('*.png')))} PNGs + gallery to {MOCKUPS_IP}/{SLUG}/")
    # 1. Ensure target dir exists.
    subprocess.run(
        ["ssh", f"root@{PVE_HOST}",
         f"pct exec {CTID} -- bash -lc "
         f"'mkdir -p /var/www/mockups/{SLUG} && rm -f /var/www/mockups/{SLUG}/*'"],
        check=True,
    )
    # 2. Tar everything in tmp_icons/, pipe to ssh → pct push via a staging file.
    staging = "/tmp/odyssey-icons.tar"
    with subprocess.Popen(
        ["tar", "-cf", "-", "-C", str(OUT), "."], stdout=subprocess.PIPE,
    ) as tar_proc:
        with open(staging, "wb") as f:
            shutil.copyfileobj(tar_proc.stdout, f)  # type: ignore[arg-type]
        tar_proc.wait()
    subprocess.run(
        ["scp", "-q", staging, f"root@{PVE_HOST}:/tmp/odyssey-icons.tar"], check=True,
    )
    subprocess.run(
        ["ssh", f"root@{PVE_HOST}",
         f"pct push {CTID} /tmp/odyssey-icons.tar /tmp/odyssey-icons.tar && "
         f"pct exec {CTID} -- bash -lc "
         f"'cd /var/www/mockups/{SLUG} && tar -xf /tmp/odyssey-icons.tar && "
         f"chown -R www-data:www-data /var/www/mockups/{SLUG} && "
         f"rm /tmp/odyssey-icons.tar' && rm /tmp/odyssey-icons.tar"],
        check=True,
    )
    pathlib.Path(staging).unlink(missing_ok=True)
    print(f"\n==> Open in browser:\n   http://{MOCKUPS_IP}/{SLUG}/")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--deploy", action="store_true",
                        help="Also push to the shared mockup host at 192.168.2.146/odyssey-icons/")
    args = parser.parse_args()

    print(f"==> Generating {len(CANDIDATES)} icon candidates via {API}")
    # Parallelize at the requests-in-flight level. The image-api on
    # CT 115 is the bottleneck (single ComfyUI worker), so limit to 3
    # concurrent requests to avoid OOMing it; remaining ones queue.
    results: list[tuple[str, str, int | None, str | None]] = []
    with cf.ThreadPoolExecutor(max_workers=3) as pool:
        futures = [pool.submit(fetch_one, name, label, prompt)
                   for name, label, prompt in CANDIDATES]
        for fut in cf.as_completed(futures):
            r = fut.result()
            filename, label, size, err = r
            results.append(r)
            if err:
                print(f"    [FAIL] {filename}: {err}", file=sys.stderr)
            else:
                print(f"    [ ok ] {filename} ({(size or 0)//1024} KB) — {label}")

    # Keep the original CANDIDATES ordering for the gallery (concurrent
    # completion above scrambles it).
    by_name = {r[0]: r for r in results}
    ordered = [by_name[name] for name, _, _ in CANDIDATES if name in by_name]
    gallery_path = write_gallery(ordered)
    print(f"\n==> Gallery written: {gallery_path}")

    if args.deploy:
        deploy_to_mockup_host()

    return 0


if __name__ == "__main__":
    sys.exit(main())
