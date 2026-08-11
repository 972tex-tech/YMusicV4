#!/usr/bin/env bash
# Drive the injected webview-js bundle in a throwaway (muted, proxied) Chrome and stream the
# [ymix] console events. Requires: `mitmdump -s test/inject.py` already running, and dist built.
#   ./test/run.sh [videoId] [watchSeconds]
set -euo pipefail
VIDEO_ID="${1:-dQw4w9WgXcQ}"
WATCH="${2:-75}"
HERE="$(cd "$(dirname "$0")" && pwd)"
PROFILE="$(mktemp -d)/chrome"
CHROME="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"

"$CHROME" --user-data-dir="$PROFILE" --proxy-server=http://127.0.0.1:8080 \
  --ignore-certificate-errors --remote-debugging-port=9222 \
  --mute-audio --autoplay-policy=no-user-gesture-required \
  --no-first-run --no-default-browser-check "about:blank" >/tmp/ymix-chrome.log 2>&1 &
CHROME_PID=$!
trap 'kill $CHROME_PID 2>/dev/null || true' EXIT
sleep 5
python3 "$HERE/drive.py" "$VIDEO_ID" "$WATCH"   # needs pychrome (pip install pychrome)
