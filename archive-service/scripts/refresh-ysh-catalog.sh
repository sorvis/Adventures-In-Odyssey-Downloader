#!/usr/bin/env bash
# Refresh the on-disk YSH album catalog at /srv/ysh_catalog.json by
# walking yourstoryhour.org/crud/product/skus. Idempotent; safe to
# re-run. Wraps `python -m app.scrape_ysh_cli` so all scrape logic
# lives in the importable Python module (testable).
#
# Usage (inside the container or with the venv active):
#   archive-service/scripts/refresh-ysh-catalog.sh
#
# Optional env vars:
#   ODYSSEY_YSH_CATALOG_PATH — override the output path
#                              (default: /srv/ysh_catalog.json)
set -euo pipefail

cd "$(dirname "$0")/.."
SVC="$PWD"

VENV="$SVC/.venv"
if [[ ! -x "$VENV/bin/python" ]]; then
  echo "Creating Python venv at $VENV"
  python3 -m venv --copies "$VENV"
fi
"$VENV/bin/pip" install --quiet -r requirements.txt

OUT_PATH="${ODYSSEY_YSH_CATALOG_PATH:-/srv/ysh_catalog.json}"
echo "Writing catalog to: $OUT_PATH"
"$VENV/bin/python" -m app.scrape_ysh_cli "$OUT_PATH"
