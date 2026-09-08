#!/bin/bash
# Starts the dashboard server (dashboard.py --serve) and a Cloudflare Quick Tunnel
# together, then prints the public https://*.trycloudflare.com URL. Ctrl-C stops both.
#
# Usage:
#   ./dashboard/serve_public.sh                      # defaults: port 8765
#   ./dashboard/serve_public.sh --port 9000
#   ./dashboard/serve_public.sh --seeds 0 1 2         # extra args are passed to dashboard.py
#
# Requires cloudflared: brew install cloudflared
# No auth layer exists (see dashboard/README.md "起動方法") — the URL is unguessable
# but unauthenticated, so anyone who obtains it can view the dashboard and write
# color presets via POST /api/color-presets.
set -euo pipefail

PORT=8765
DASHBOARD_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift 2 ;;
        *) DASHBOARD_ARGS+=("$1"); shift ;;
    esac
done

if ! command -v cloudflared >/dev/null 2>&1; then
    echo "cloudflared not found. Install it with: brew install cloudflared" >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CF_LOG="$(mktemp -t cloudflared_dashboard)"

DASH_PID=""
CF_PID=""
cleanup() {
    echo
    echo "Stopping dashboard server and tunnel..."
    [ -n "$DASH_PID" ] && kill "$DASH_PID" 2>/dev/null || true
    [ -n "$CF_PID" ] && kill "$CF_PID" 2>/dev/null || true
    rm -f "$CF_LOG"
}
trap cleanup EXIT INT TERM

echo "Starting dashboard server on 127.0.0.1:$PORT ..."
python3 "$SCRIPT_DIR/dashboard.py" --serve --port "$PORT" ${DASHBOARD_ARGS[@]+"${DASHBOARD_ARGS[@]}"} &
DASH_PID=$!

sleep 1

echo "Starting Cloudflare Quick Tunnel..."
cloudflared tunnel --url "http://127.0.0.1:$PORT" > "$CF_LOG" 2>&1 &
CF_PID=$!

echo "Waiting for public URL..."
URL=""
for _ in $(seq 1 30); do
    URL=$(grep -o 'https://[a-zA-Z0-9.-]*\.trycloudflare\.com' "$CF_LOG" | head -n1 || true)
    [ -n "$URL" ] && break
    sleep 1
done

echo
if [ -n "$URL" ]; then
    echo "Dashboard is publicly reachable at: $URL"
    echo "(local: http://127.0.0.1:$PORT — this URL changes every time this script restarts)"
else
    echo "Could not detect the tunnel URL within 30s — check the log below:"
    cat "$CF_LOG"
fi
echo "Ctrl-C to stop both the server and the tunnel."
echo

wait "$DASH_PID" "$CF_PID"
