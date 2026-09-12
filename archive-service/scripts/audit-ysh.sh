#!/usr/bin/env bash
# Audit the Your Story Hour library in the NAS archive.
#
# Answers two questions for every YSH episode, by transcribing a short
# clip of the actual audio on the whisperx GPU box:
#
#   1. Does this audio really belong to Your Story Hour? Scored off
#      station-ID phrases ("...has been Your Story Hour, from Berrien
#      Springs") vs. another show's branding ("Adventures in Odyssey",
#      "Whit's End"). Catches a mis-ingested episode even when its
#      title matches nothing in the catalog.
#   2. Does the announced story title match the row's stored title?
#      The YSH storyteller says "I call my story, <title>" in the
#      cold-open; that's matched against the full 1055-track
#      yourstoryhour.org catalog.
#
# Plus a free, audio-less cross-check of every row's title/album
# against the catalog entry for its `ysh-sku-<id>` external_id.
#
# READ-ONLY. It reports; it never renames, deletes, or stamps
# title_validated_at. Act on findings yourself (or via
# `whisper_titles.py apply`) once you agree with the call.
#
# Run it from the dev box (CT 120) — it needs ffmpeg locally, HTTP to
# the archive-service, and ssh to the Proxmox host that owns the
# whisperx LXC.
#
# Usage:
#   archive-service/scripts/audit-ysh.sh                 # full sweep (~101 eps)
#   archive-service/scripts/audit-ysh.sh --quick         # metadata only, no GPU
#   archive-service/scripts/audit-ysh.sh --limit 10      # smoke run
#   archive-service/scripts/audit-ysh.sh --report FILE   # re-print an old report
#   archive-service/scripts/audit-ysh.sh --strict        # exit 1 on findings
#
# Anything else is forwarded to `whisper_titles.py audit-ysh`
# (--threshold, --head-secs, --offset, --batch-size, --album, …).
#
# Token resolution, in order: $ODYSSEY_NAS_TOKEN, ~/.aio-archive-token,
# then the archive-service LXC's .env read through the Proxmox host.
#
# Overrides (env): ODYSSEY_NAS_URL, ODYSSEY_NAS_TOKEN, PROXMOX_HOST,
#                  LXC_ID, WHISPERX_CT, ODYSSEY_YSH_REPORT
#
# Exit codes: 0 = ran (see the summary), 1 = findings under --strict,
#             2 = transport/credential/prerequisite failure.
set -euo pipefail

cd "$(dirname "$0")/.."
SVC="$PWD"

NAS_URL="${ODYSSEY_NAS_URL:-http://192.168.2.142:8088}"
PROXMOX_HOST="${PROXMOX_HOST:-root@192.168.2.123}"
LXC_ID="${LXC_ID:-121}"
LXC_ENV_PATH="${LXC_ENV_PATH:-/opt/archive-service/.env}"
WHISPERX_CT="${WHISPERX_CT:-112}"
REPORT="${ODYSSEY_YSH_REPORT:-/tmp/ysh-audit.json}"

step() { printf '\n\033[1;34m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[fail]\033[0m %s\n' "$*" >&2; exit 2; }

# --------------------- argument pre-pass ---------------------
# --quick / --report are wrapper-level sugar; everything else is
# forwarded verbatim to the Python subcommand.
NEED_GPU=1
PASSTHRU=()
FROM_REPORT=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --quick)   NEED_GPU=0; PASSTHRU+=(--no-audio); shift ;;
    --no-audio) NEED_GPU=0; PASSTHRU+=(--no-audio); shift ;;
    --report)  FROM_REPORT="$2"; NEED_GPU=0; shift 2 ;;
    *)         PASSTHRU+=("$1"); shift ;;
  esac
done

# Re-printing an old report touches nothing — skip every precondition.
if [[ -n "$FROM_REPORT" ]]; then
  [[ -f "$FROM_REPORT" ]] || die "no such report: $FROM_REPORT"
  exec python3 scripts/whisper_titles.py audit-ysh \
    --from-report "$FROM_REPORT" "${PASSTHRU[@]}"
fi

# --------------------- preconditions ---------------------
# Fail here with a clear message rather than 40 minutes in, mid-batch.
step "Checking prerequisites"
command -v ffmpeg  >/dev/null || die "ffmpeg not on PATH (apt install ffmpeg)"
command -v ffprobe >/dev/null || die "ffprobe not on PATH (apt install ffmpeg)"
echo "    ffmpeg/ffprobe: ok"

if [[ $NEED_GPU -eq 1 ]]; then
  ssh -o BatchMode=yes -o ConnectTimeout=8 "$PROXMOX_HOST" true 2>/dev/null \
    || die "no passwordless ssh to $PROXMOX_HOST (override with PROXMOX_HOST=)"
  ssh -o BatchMode=yes -o ConnectTimeout=8 "$PROXMOX_HOST" \
    "pct exec $WHISPERX_CT -- test -x /root/whisper-venv/bin/whisperx" 2>/dev/null \
    || die "whisperx not found in CT $WHISPERX_CT on $PROXMOX_HOST"
  echo "    whisperx on CT $WHISPERX_CT via $PROXMOX_HOST: ok"
fi

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
echo "    archive-service at $NAS_URL: ok"

# --------------------- run ---------------------
if [[ $NEED_GPU -eq 1 ]]; then
  step "Auditing YSH library (downloads audio + whisperx on CT $WHISPERX_CT)"
  echo "    this is the slow path — ~2 clips per episode, batched"
else
  step "Auditing YSH library (catalog metadata only)"
fi
echo "    report: $REPORT"

# The service venv if run-tests.sh bootstrapped one, else system python.
# whisper_titles.py is stdlib-only, so either works.
PY="python3"
[[ -x "$SVC/.venv/bin/python" ]] && PY="$SVC/.venv/bin/python"

set +e
"$PY" scripts/whisper_titles.py audit-ysh \
  --base-url "$NAS_URL" --token "$TOKEN" \
  --pve "$PROXMOX_HOST" --whisperx-ct "$WHISPERX_CT" \
  --out "$REPORT" "${PASSTHRU[@]}"
rc=$?
set -e

echo "    full JSON report: $REPORT"
echo "    re-read it any time with: $0 --report $REPORT"
exit $rc
