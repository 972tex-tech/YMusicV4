#!/bin/bash
# Lock-screen variant of the matrix: backgrounds the app by locking the simulator
# (Simulator.app menu Device > Lock via Cmd+L AppleScript) instead of app-switching.
# Collects into results-lock/.
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p results-lock

lock_sim() {
    osascript -e 'tell application "Simulator" to activate' \
              -e 'delay 0.5' \
              -e 'tell application "System Events" to keystroke "l" using command down'
}

run_one() { # NAME BUNDLE_ID MODE
    local NAME=$1 BID=$2 MODE=$3
    echo "=== lock: $NAME mode=$MODE ==="
    xcrun simctl terminate booted "$BID" 2>/dev/null
    sleep 1
    xcrun simctl launch booted "$BID" -mode "$MODE" || { echo "LAUNCH FAILED"; return 1; }
    sleep 8
    lock_sim || { echo "LOCK FAILED"; return 1; }
    sleep 35
    xcrun simctl terminate booted "$BID" 2>/dev/null
    sleep 1
    local CONT
    CONT=$(xcrun simctl get_app_container booted "$BID" data)
    cp "$CONT/Documents/spike-log.csv" "results-lock/${NAME}-${MODE}.csv"
    echo "    -> results-lock/${NAME}-${MODE}.csv ($(wc -l < "results-lock/${NAME}-${MODE}.csv" | tr -d ' ') lines)"
    # wake the sim back up for the next run (Cmd+L toggles; home key also wakes)
    osascript -e 'tell application "Simulator" to activate' \
              -e 'delay 0.5' \
              -e 'tell application "System Events" to keystroke "h" using {command down, shift down}'
    sleep 2
}

run_one BgAudioSpike com.ymusic.bgspike plain
run_one BgAudioSpike com.ymusic.bgspike session
run_one BgAudioSpike com.ymusic.bgspike native
run_one BgAudioSpikeNoBG com.ymusic.bgspike.nobg session
run_one BgAudioSpikeNoBG com.ymusic.bgspike.nobg native

echo "lock matrix done"
