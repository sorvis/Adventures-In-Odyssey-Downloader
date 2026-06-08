#!/usr/bin/env bash
# Backfill the `album` column on YSH episodes that landed in Unsorted.
#
# Usage:
#   ./archive-service/scripts/backfill-ysh-albums.sh                # apply
#   ./archive-service/scripts/backfill-ysh-albums.sh --dry-run      # preview
#   ./archive-service/scripts/backfill-ysh-albums.sh --refresh      # re-scrape catalog first
#
# Idempotent: re-running after a successful pass is a no-op.
# Refreshes the YSH catalog automatically when /srv/ysh_catalog.json
# is missing.
set -euo pipefail

cd "$(dirname "$0")/.."

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

if ! docker compose ps --status running 2>/dev/null | grep -q odyssey-archive; then
  echo "error: odyssey-archive container is not running. Start it with scripts/up.sh" >&2
  exit 1
fi

step "Backfilling YSH album fields ($*)"
docker compose exec -T archive python -m app.backfill_ysh_albums "$@"
