#!/usr/bin/env bash
# Backfill the `description` column on AIO episodes whose value is NULL.
#
# Walks oneplace.com's related-episodes API backwards from the most
# recent missing eid, matches each result against our DB, and PATCHes
# the description in place. Idempotent.
#
# Usage:
#   ./archive-service/scripts/backfill-aio-descriptions.sh             # apply
#   ./archive-service/scripts/backfill-aio-descriptions.sh --dry-run   # preview
#   ./archive-service/scripts/backfill-aio-descriptions.sh --max-pages 50
#
# What this CAN'T fix: pre-oneplace back-catalog episodes (low broadcast
# numbers like 82 "Heatwave") — their CMS ids predate oneplace and
# aren't fetchable via this API. Those get counted as "unreachable".
set -euo pipefail

cd "$(dirname "$0")/.."

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }

if ! docker compose ps --status running 2>/dev/null | grep -q odyssey-archive; then
  echo "error: odyssey-archive container is not running. Start it with scripts/up.sh" >&2
  exit 1
fi

step "Backfilling AIO descriptions ($*)"
docker compose exec -T archive python -m app.backfill_aio_descriptions "$@"
