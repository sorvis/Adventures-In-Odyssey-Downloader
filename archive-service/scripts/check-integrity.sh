#!/usr/bin/env bash
# Check every archived episode for truncated or corrupt audio.
#
# Catches the failure where a download stopped partway through and the
# partial file was archived as though complete: it plays, it looks
# fine in the app, and it just ends in the middle of the story.
#
# The test is one each file makes against itself. Every MP3 here
# carries a Xing/Info header declaring how many bytes the complete file
# should have; comparing that to the real size finds a short file
# immediately. That needs only the first ~16 KB of each episode, so a
# full sweep is a few hundred small range requests rather than ~10 GB
# of downloads — and needs no ffmpeg anywhere.
#
# Read-only. It reports; it never deletes or re-downloads anything.
#
# Usage:
#   archive-service/scripts/check-integrity.sh                 # full sweep
#   archive-service/scripts/check-integrity.sh --provider ysh  # one show
#   archive-service/scripts/check-integrity.sh --limit 20      # smoke run
#   archive-service/scripts/check-integrity.sh --deep          # + sha256 verify
#   archive-service/scripts/check-integrity.sh --json          # machine-readable
#
# --deep re-downloads every episode in full to verify its stored
# sha256, which catches corruption that preserves file length. That
# moves the whole archive over the network; the default sweep does not.
#
# Token resolution, in order: $ODYSSEY_NAS_TOKEN, ~/.aio-archive-token,
# then the archive-service LXC's .env read through the Proxmox host.
#
# Overrides (env): ODYSSEY_NAS_URL, ODYSSEY_NAS_TOKEN, PROXMOX_HOST,
#                  LXC_ID, ODYSSEY_INTEGRITY_REPORT
#
# Exit codes: 0 = every file intact, 1 = at least one problem,
#             2 = transport/credential error.
set -euo pipefail

cd "$(dirname "$0")/.."
SVC="$PWD"

NAS_URL="${ODYSSEY_NAS_URL:-http://192.168.2.142:8088}"
# The IP rather than proxmox.lan: the .lan name doesn't resolve on this
# network, and defaulting to something unreachable makes the script
# fail on token resolution with a misleading message.
PROXMOX_HOST="${PROXMOX_HOST:-root@192.168.2.123}"
LXC_ID="${LXC_ID:-121}"
LXC_ENV_PATH="${LXC_ENV_PATH:-/opt/archive-service/.env}"
REPORT="${ODYSSEY_INTEGRITY_REPORT:-/tmp/odyssey-integrity.json}"

die() { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 2; }

# --------------------- token resolution ---------------------
TOKEN="${ODYSSEY_NAS_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$HOME/.aio-archive-token" ]]; then
  TOKEN="$(cat "$HOME/.aio-archive-token")"
fi
if [[ -z "$TOKEN" ]]; then
  # Captured into a variable, never echoed.
  TOKEN="$(ssh -o BatchMode=yes -o ConnectTimeout=8 "$PROXMOX_HOST" \
    "pct exec $LXC_ID -- grep -oP 'ODYSSEY_AUTH_TOKEN=\K.*' $LXC_ENV_PATH" \
    2>/dev/null || true)"
fi
[[ -n "$TOKEN" ]] || die "could not resolve a bearer token (set ODYSSEY_NAS_TOKEN,
       write ~/.aio-archive-token, or make CT $LXC_ID reachable via $PROXMOX_HOST)"

curl -fsS -m 10 -o /dev/null "$NAS_URL/healthz" \
  || die "archive-service not reachable at $NAS_URL"

# The checker is stdlib-only; prefer the service venv if it exists.
PY="python3"
[[ -x "$SVC/.venv/bin/python" ]] && PY="$SVC/.venv/bin/python"

exec "$PY" scripts/check_audio_integrity.py \
  --base-url "$NAS_URL" --token "$TOKEN" --out "$REPORT" "$@"
