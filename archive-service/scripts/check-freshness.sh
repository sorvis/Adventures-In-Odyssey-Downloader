#!/usr/bin/env bash
# Manual diagnostic: report AIO episodes oneplace.com has aired that
# never reached the NAS archive (a hole in the
# oneplace → Android app → archive-service pipeline). Read-only — it
# only reports; it never re-ingests or re-archives.
#
# Run from the dev machine. Talks to oneplace.com (public) and the
# archive-service HTTP API (bearer-token auth). The bearer token is
# resolved from, in order:
#   1. $ODYSSEY_NAS_TOKEN
#   2. ~/.aio-archive-token
#   3. the .env on the archive-service LXC, read via the Proxmox host
#      (same host/CT/path deploy.sh uses)
#
# Usage:
#   archive-service/scripts/check-freshness.sh                 # table report
#   archive-service/scripts/check-freshness.sh --json          # machine-readable
#   archive-service/scripts/check-freshness.sh --probe-window 80
#   ODYSSEY_NAS_URL=http://host:8088 archive-service/scripts/check-freshness.sh
#
# Overrides (env): ODYSSEY_NAS_URL, ODYSSEY_NAS_TOKEN, PROXMOX_HOST, LXC_ID
#
# Exit code passes through from the checker: 0 = fully archived,
# 1 = at least one gap, 2 = transport/credential error.
set -euo pipefail

cd "$(dirname "$0")/.."
SVC="$PWD"

NAS_URL="${ODYSSEY_NAS_URL:-http://192.168.2.142:8088}"
PROXMOX_HOST="${PROXMOX_HOST:-root@proxmox.lan}"
LXC_ID="${LXC_ID:-121}"
LXC_ENV_PATH="${LXC_ENV_PATH:-/opt/archive-service/.env}"

# --------------------- token resolution ---------------------
TOKEN="${ODYSSEY_NAS_TOKEN:-}"
if [[ -z "$TOKEN" && -f "$HOME/.aio-archive-token" ]]; then
  TOKEN="$(cat "$HOME/.aio-archive-token")"
fi
if [[ -z "$TOKEN" ]]; then
  # Last resort: pull ODYSSEY_AUTH_TOKEN out of the LXC's .env. The
  # value is captured into a variable and never echoed.
  TOKEN="$(ssh -o ConnectTimeout=8 "$PROXMOX_HOST" \
    "pct exec $LXC_ID -- grep -oP 'ODYSSEY_AUTH_TOKEN=\K.*' $LXC_ENV_PATH" 2>/dev/null || true)"
fi
if [[ -z "$TOKEN" ]]; then
  cat >&2 <<EOF
error: could not resolve a NAS bearer token. Provide one via:
  - ODYSSEY_NAS_TOKEN env var, or
  - ~/.aio-archive-token file, or
  - reachable Proxmox host ($PROXMOX_HOST) exposing CT $LXC_ID's $LXC_ENV_PATH
    (override with PROXMOX_HOST=root@<ip> LXC_ID=<n>)
EOF
  exit 2
fi

# The checker is stdlib-only; prefer the service venv if it's been
# bootstrapped (scripts/run-tests.sh creates it), else system python3.
if [[ -x "$SVC/.venv/bin/python" ]]; then
  PY="$SVC/.venv/bin/python"
else
  PY="python3"
fi

exec "$PY" scripts/check_archive_freshness.py \
  --nas-url "$NAS_URL" --nas-token "$TOKEN" "$@"
