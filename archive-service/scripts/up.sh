#!/usr/bin/env bash
# One-liner bootstrap for archive-service.
#
# What it does (each step idempotent):
#   1. cd into archive-service/ regardless of where you ran it from.
#   2. If .env doesn't exist, copy .env.example → .env and generate a
#      fresh ODYSSEY_AUTH_TOKEN with `openssl rand -hex 32`.
#   3. `docker compose up -d --build` to start (or rebuild + restart) the service.
#   4. Curl /healthz from the host until it responds (30s cap).
#   5. Print the URL + token so you can paste them into the Android app.
#
# Usage (from anywhere):
#   ./archive-service/scripts/up.sh
#
# Override host-side data path (Proxmox/NAS):
#   ODYSSEY_DATA_HOST_DIR=/data ./archive-service/scripts/up.sh
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v docker >/dev/null; then
  echo "error: docker is not installed on this host" >&2
  exit 1
fi
# Detect 'docker compose' (v2, plugin) or 'docker-compose' (v1, legacy).
if docker compose version >/dev/null 2>&1; then
  COMPOSE="docker compose"
elif command -v docker-compose >/dev/null; then
  COMPOSE="docker-compose"
else
  echo "error: neither 'docker compose' nor 'docker-compose' found" >&2
  exit 1
fi

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

# ---------- 1. .env ----------
if [[ ! -f .env ]]; then
  step "Generating .env (first run)"
  cp .env.example .env
  TOKEN="$(openssl rand -hex 32)"
  # Replace the placeholder line — works on GNU sed and BSD sed.
  if sed --version >/dev/null 2>&1; then
    sed -i "s|^ODYSSEY_AUTH_TOKEN=.*|ODYSSEY_AUTH_TOKEN=${TOKEN}|" .env
  else
    sed -i '' "s|^ODYSSEY_AUTH_TOKEN=.*|ODYSSEY_AUTH_TOKEN=${TOKEN}|" .env
  fi
  echo "    new token written to .env"
else
  echo "    .env already exists — leaving it alone"
fi

# Sourcing .env so the data-dir default (./_data) gets created before
# compose tries to bind-mount it (Docker creates it as root otherwise).
set -a; . ./.env; set +a
DATA_HOST_DIR="${ODYSSEY_DATA_HOST_DIR:-./_data}"
mkdir -p "$DATA_HOST_DIR"

# ---------- 2. compose up ----------
step "Bringing up odyssey-archive"
$COMPOSE up -d --build

# ---------- 3. wait for /healthz ----------
PORT="${ODYSSEY_PORT:-8088}"
URL="http://127.0.0.1:${PORT}/healthz"
step "Waiting for $URL"
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 "$URL" >/dev/null 2>&1; then
    echo "    OK"
    break
  fi
  sleep 1
done

# ---------- 4. summary ----------
TOKEN_LINE=$(grep -E '^ODYSSEY_AUTH_TOKEN=' .env | head -1 | cut -d= -f2-)
cat <<EOF

\033[1;32m✔ odyssey-archive is up.\033[0m

  URL:       http://$(hostname -I 2>/dev/null | awk '{print $1}'):${PORT}
  Token:     ${TOKEN_LINE}
  Data dir:  ${DATA_HOST_DIR}
  Logs:      ${COMPOSE} logs -f archive
  Stop:      ${COMPOSE} down

Paste the URL + token into the Android app's Settings.
EOF
