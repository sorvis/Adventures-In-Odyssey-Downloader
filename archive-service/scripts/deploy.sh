#!/usr/bin/env bash
# Deploy this repo's archive-service to CT 121 on the Proxmox host.
#
# Run from the dev machine — packages the working tree's
# archive-service/, pushes the tarball through the Proxmox host into
# the LXC, extracts it over the existing install (`.env` excluded so
# the bearer token survives), and runs scripts/up.sh inside the
# container to rebuild + restart.
#
# Output contract (matches scripts/release.sh + scripts/run-android-
# tests.sh): a tight ==> step line per phase, full noisy output
# captured to /tmp/odyssey-deploy.log so callers can run this bare.
# On failure, the load-bearing tail of the log is auto-printed.
#
# Usage:
#   archive-service/scripts/deploy.sh                  # deploy current working tree
#   PROXMOX_HOST=root@... LXC_ID=999 archive-service/scripts/deploy.sh   # overrides
set -euo pipefail

cd "$(dirname "$0")/../.."          # repo root
ROOT="$PWD"

PROXMOX_HOST="${PROXMOX_HOST:-root@proxmox.lan}"
LXC_ID="${LXC_ID:-121}"
LXC_APP_PATH="${LXC_APP_PATH:-/opt/archive-service}"
LOG="${ODYSSEY_DEPLOY_LOG:-/tmp/odyssey-deploy.log}"

# All noisy command output (scp, pct push, container build) lands in
# the log. Step headers + the final summary print to stdout/stderr
# directly.
: > "$LOG"
step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
fail() {
  printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2
  echo "--- last 30 lines of $LOG ---" >&2
  tail -30 "$LOG" >&2
  exit 1
}

# --------------------- 1. package ---------------------
step "Packaging archive-service/ from $ROOT"
TARBALL=$(mktemp /tmp/archive-deploy-XXXXXX.tgz)
trap 'rm -f "$TARBALL"' EXIT
tar --exclude='archive-service/_data' \
    --exclude='archive-service/.venv' \
    --exclude='archive-service/.pytest_cache' \
    --exclude='archive-service/__pycache__' \
    --exclude='archive-service/**/__pycache__' \
    --exclude='archive-service/.coverage' \
    --exclude='archive-service/.env' \
    -C "$ROOT" -czf "$TARBALL" archive-service/ >>"$LOG" 2>&1 \
    || fail "tar failed"
echo "    bundle: $(du -h "$TARBALL" | cut -f1)   log: $LOG"

# --------------------- 2. push ---------------------
step "Pushing to $PROXMOX_HOST → CT $LXC_ID"
scp -q "$TARBALL" "$PROXMOX_HOST:/tmp/archive-deploy.tgz" >>"$LOG" 2>&1 \
    || fail "scp to $PROXMOX_HOST failed"
ssh "$PROXMOX_HOST" bash >>"$LOG" 2>&1 <<EOF || fail "pct push / extract failed"
set -e
pct push $LXC_ID /tmp/archive-deploy.tgz /tmp/archive-deploy.tgz
pct exec $LXC_ID -- bash -c '
  set -e
  cd /opt
  tar xzf /tmp/archive-deploy.tgz
  rm /tmp/archive-deploy.tgz
'
rm /tmp/archive-deploy.tgz
EOF
echo "    ok"

# --------------------- 3. rebuild + restart ---------------------
step "Rebuilding + restarting container"
ssh "$PROXMOX_HOST" "pct exec $LXC_ID -- bash -lc '
  cd $LXC_APP_PATH
  ODYSSEY_DATA_HOST_DIR=/data scripts/up.sh
'" >>"$LOG" 2>&1 \
    || fail "scripts/up.sh inside container failed"
URL=$(grep -oE 'http://[^[:space:]]+:8088' "$LOG" | tail -1 || true)
echo "    ok"

# --------------------- 4. summary ---------------------
printf '\n\033[1;32m✔ archive-service deployed\033[0m\n'
echo "    URL : ${URL:-http://archive.lan:8088}"
echo "    log : $LOG"
