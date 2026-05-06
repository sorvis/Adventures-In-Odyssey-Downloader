#!/usr/bin/env bash
# One-liner trigger for the drop-folder importer.
#
# Workflow:
#   1. Drop arbitrary mp3s into /data/import/ on the LXC (SCP, NFS,
#      whatever). Filenames can be any shape.
#   2. Run: ./archive-service/scripts/run-import.sh
#   3. Files matched against the bundled AIO catalog land in
#      /data/audio/<album-slug>/<id>-<title-slug>.mp3 with a row in
#      episodes.db. Unmatched files move to /data/import/_unmatched/
#      so the user can rename + re-drop.
#
# Idempotent: re-running after a successful pass is a no-op.
set -euo pipefail

cd "$(dirname "$0")/.."

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

if ! docker compose ps --status running 2>/dev/null | grep -q odyssey-archive; then
  echo "error: odyssey-archive container is not running. Start it with scripts/up.sh" >&2
  exit 1
fi

step "Running drop-folder importer (logs at end)"
docker compose exec -T archive python -m app.import_dropbox "$@"
