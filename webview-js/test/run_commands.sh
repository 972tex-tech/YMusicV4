#!/usr/bin/env bash
# End-to-end sweep of every ymusicAction command against the injected bundle, in a throwaway MUTED,
# proxied Chrome. Requires: dist built, `pip install pychrome`.  ./test/run_commands.sh [videoId] [mobile|desktop]
set -euo pipefail
VIDEO_ID="${1:-dQw4w9WgXcQ}"
PLATFORM="${2:-mobile}"
HERE="$(cd "$(dirname "$0")" && pwd)"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

mitmdump -s "$HERE/inject.py" --set connection_strategy=lazy >/tmp/ymix-mitm.log 2>&1 &
MITM_PID=$!
"$CHROME" --user-data-dir="$(mktemp -d)/chrome" --proxy-server=http://127.0.0.1:8080 \
  --ignore-certificate-errors --remote-debugging-port=9222 \
  --mute-audio --autoplay-policy=no-user-gesture-required \
  --no-first-run --no-default-browser-check about:blank >/tmp/ymix-chrome.log 2>&1 &
CHROME_PID=$!
trap 'kill $CHROME_PID $MITM_PID 2>/dev/null || true' EXIT
sleep 7
python3 "$HERE/commands.py" "$VIDEO_ID" "$PLATFORM"
