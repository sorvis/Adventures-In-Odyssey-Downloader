#!/usr/bin/env bash
# One-liner trigger for the drop-folder importer.
#
# Usage:
#   ./archive-service/scripts/run-import.sh                  # AIO (default)
#   ./archive-service/scripts/run-import.sh --provider ysh   # YSH
#
# AIO workflow:
#   1. Drop arbitrary mp3s into /data/import/ on the LXC (SCP, NFS,
#      whatever). Filenames can be any shape.
#   2. Run: ./archive-service/scripts/run-import.sh
#   3. Files matched against the bundled AIO catalog land in
#      /data/audio/aio/<album-slug>/<id>-<title-slug>.mp3 with a row
#      in episodes.db. Unmatched files move to /data/import/_unmatched/
#      so the user can rename + re-drop.
#
# YSH workflow:
#   1. Drop YSH mp3s into /data/import/ on the LXC. Filenames should
#      follow the publisher's convention: "<CODE>-<VOL>-<TRACK> -
#      <Title>.mp3" (e.g. "EE-11-02 - Madeleine's Courage.mp3"). Plain
#      title-only filenames work too if the title is unique across
#      the catalog.
#   2. Run: ./archive-service/scripts/run-import.sh --provider ysh
#   3. Files matched against the YSH catalog land in
#      /data/audio/ysh/<album-slug>/<sku_id>-<title-slug>.mp3 with a
#      row in episodes.db keyed on provider_id='ysh'.
#
# Refresh the YSH catalog before first use:
#   ./archive-service/scripts/refresh-ysh-catalog.sh
#
# Idempotent: re-running after a successful pass is a no-op.
set -euo pipefail

cd "$(dirname "$0")/.."

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

PROVIDER="aio"
PASSTHROUGH=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --provider)
      PROVIDER="$2"; shift 2 ;;
    --provider=*)
      PROVIDER="${1#--provider=}"; shift ;;
    *)
      PASSTHROUGH+=("$1"); shift ;;
  esac
done

case "$PROVIDER" in
  aio) MODULE="app.import_dropbox" ;;
  ysh) MODULE="app.import_dropbox_ysh" ;;
  *)
    echo "error: unknown --provider '$PROVIDER' (expected: aio | ysh)" >&2
    exit 2 ;;
esac

if ! docker compose ps --status running 2>/dev/null | grep -q odyssey-archive; then
  echo "error: odyssey-archive container is not running. Start it with scripts/up.sh" >&2
  exit 1
fi

step "Running drop-folder importer for $PROVIDER (logs at end)"
docker compose exec -T archive python -m "$MODULE" "${PASSTHROUGH[@]}"
