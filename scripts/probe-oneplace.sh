#!/usr/bin/env bash
#
# Live diagnostic for the oneplace.com scraper path used by
# AioOneplaceProvider / YshFreeStreamProvider. Run this when:
#
#   - DailyCheckWorker reports `provider 'aio' newSince returned 0`
#   - The Recent tab for a show is mysteriously empty
#   - You suspect oneplace.com changed its HTML or API shape
#
# What it does:
#   1. Fetches the show's /listen/ page with the same User-Agent
#      OneplaceClient uses, following redirects and decompressing
#      gzip. Confirms the page is reachable.
#   2. Runs the bootstrap regex (`episodeId[=:"\s]+(\d{6,})`) and
#      reports which `episodeId=NNNNNNN` value the app would extract.
#   3. Hits the /api/related-episodes endpoint at the bootstrap eid
#      AND at eid+1..eid+10, dumping the showId distribution of each
#      response. This is the probe-forward loop the app walks; if
#      every page returns the wrong showId, the app gives up after
#      GAP_PROBE_CAP=50 and reports 0 episodes.
#
# Reading the output: a healthy AIO state looks like at least one of
# the probed pages returning items with showId=777. A SOLELY non-777
# result across 10+ probes means oneplace's API semantics shifted
# and the app's cursor strategy needs a code change — see
# OneplaceClient.kt:96+.
#
# History:
#   2026-05-31 — wrote this after AIO Recent was silently empty for
#   weeks. Root cause turned out to be: oneplace's listen page now
#   embeds an *anchor* eid (older AIO episode used as a featured
#   pointer) instead of the global newest eid. The app's
#   `cursor = latest + 1` strategy walked forward into non-AIO show
#   gaps; querying the seed directly returns recent AIO.

set -euo pipefail

SHOW_SLUG="${1:-adventures-in-odyssey}"
EXPECTED_SHOW_ID="${2:-777}"

LISTEN_URL="https://www.oneplace.com/ministries/${SHOW_SLUG}/listen/"
API_URL="https://www.oneplace.com/api/related-episodes"
UA="Mozilla/5.0 (Android) odyssey-app/0.1"
ACCEPT="application/json, text/html, */*"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
dim()  { printf '\033[2m%s\033[0m\n' "$*"; }

bold "== 1. Listen page reachability =="
TMP_HTML=$(mktemp /tmp/oneplace-listen.XXXXXX.html)
trap 'rm -f "$TMP_HTML"' EXIT
HTTP=$(curl -sSL --compressed --max-time 30 \
  -H "User-Agent: ${UA}" -H "Accept: ${ACCEPT}" \
  -o "$TMP_HTML" \
  -w "%{http_code} %{url_effective} %{size_download}\n" \
  "$LISTEN_URL")
echo "  $HTTP"

bold "== 2. Bootstrap regex =="
# Matches the same pattern as OneplaceClient.kt's `bootstrapRe`.
BOOTSTRAP_EID=$(python3 -c "
import re,sys
html=open('$TMP_HTML').read()
m=re.search(r'episodeId[=:\"\s]+(\d{6,})', html)
print(m.group(1) if m else 'NO_MATCH')
")
echo "  bootstrap episodeId = ${BOOTSTRAP_EID}"
if [[ "$BOOTSTRAP_EID" == "NO_MATCH" ]]; then
  bold "  ✗ Bootstrap regex did not match — oneplace HTML changed shape."
  bold "    Open ${LISTEN_URL} in a browser and grep for 'episodeId' to find the new variable name."
  exit 1
fi

bold "== 3. related-episodes probe (eid → eid+10) =="
echo "  expected showId for ${SHOW_SLUG} = ${EXPECTED_SHOW_ID}"
echo
printf "  %-12s %-7s %-30s %s\n" "seed" "count" "showIds (count by id)" "first 3 eids returned"
echo "  ----------------------------------------------------------------------------"
hit=0
for offset in 0 1 2 3 4 5 6 7 8 9 10; do
  seed=$((BOOTSTRAP_EID + offset))
  body=$(curl -sSL --compressed --max-time 20 \
    -H "User-Agent: ${UA}" -H "Accept: ${ACCEPT}" \
    "${API_URL}?eid=${seed}&ps=20&watch=false") || body="[]"
  python3 -c "
import json,sys
try: d=json.loads('''$body''')
except: d=[]
from collections import Counter
c=Counter(e.get('showId') for e in d)
ids=[str(e.get('episodeId')) for e in d[:3]]
shown=','.join(f'{k}:{v}' for k,v in sorted(c.items(), key=lambda x:(-x[1], str(x[0]))))
print(f'  {$seed:<12} {len(d):<7} {shown:<30} {\",\".join(ids)}')
"
  has_target=$(python3 -c "
import json
try: d=json.loads('''$body''')
except: d=[]
print(1 if any(e.get('showId') == $EXPECTED_SHOW_ID for e in d) else 0)
")
  if [[ "$has_target" == "1" ]]; then
    hit=$((hit + 1))
  fi
done

echo
if (( hit > 0 )); then
  bold "== ✓ Found ${hit} probe(s) returning showId=${EXPECTED_SHOW_ID} =="
  echo "  AioOneplaceProvider's newSince should be returning content."
  echo "  If it isn't, suspect the cursor=latest+1 strategy: it skips the"
  echo "  bootstrap eid itself, and if the bootstrap is an anchor (not the"
  echo "  real newest), forward-walking +1..+50 misses the show entirely."
  echo "  Quick test: change OneplaceClient.kt:109  \`var cursor = latest + 1\`"
  echo "  to  \`var cursor = latest\`  and re-run this probe."
else
  bold "== ✗ NO probe returned showId=${EXPECTED_SHOW_ID} within +0..+10 =="
  echo "  Either the show isn't publishing (unlikely for AIO/YSH) OR"
  echo "  oneplace changed how it surfaces show identity. Inspect a few"
  echo "  pages by hand to see what showId fields the API now emits."
fi
