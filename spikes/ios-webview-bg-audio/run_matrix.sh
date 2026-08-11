#!/bin/bash
# Runs the workaround matrix on the booted simulator.
# Per run: launch fresh -> autoplay starts ~2.5s in -> 8s foreground -> background via
# Settings -> 35s window (spans track1's end at ~22s, so the in-background track change
# is exercised) -> foreground via URL scheme -> 6s -> terminate -> collect the CSV log.
set -uo pipefail
cd "$(dirname "$0")"
mkdir -p results

run_one() { # NAME BUNDLE_ID SCHEME MODE
    local NAME=$1 BID=$2 SCHEME=$3 MODE=$4
    echo "=== $NAME mode=$MODE ==="
    xcrun simctl terminate booted "$BID" 2>/dev/null
    sleep 1
    xcrun simctl launch booted "$BID" -mode "$MODE" || { echo "LAUNCH FAILED"; return 1; }
    sleep 8
    xcrun simctl launch booted com.apple.Preferences >/dev/null 2>&1
    sleep 35
    xcrun simctl openurl booted "$SCHEME://foreground" 2>/dev/null
    sleep 6
    xcrun simctl terminate booted "$BID" 2>/dev/null
    sleep 1
    local CONT
    CONT=$(xcrun simctl get_app_container booted "$BID" data)
    cp "$CONT/Documents/spike-log.csv" "results/${NAME}-${MODE}.csv"
    echo "    -> results/${NAME}-${MODE}.csv ($(wc -l < "results/${NAME}-${MODE}.csv" | tr -d ' ') lines)"
}

xcrun simctl install booted build/BgAudioSpike.app
xcrun simctl install booted build/BgAudioSpikeNoBG.app

for MODE in plain session nowplaying silence kick private all native; do
    run_one BgAudioSpike com.ymusic.bgspike bgspike "$MODE"
done
for MODE in session native; do
    run_one BgAudioSpikeNoBG com.ymusic.bgspike.nobg bgspike2 "$MODE"
done

echo "matrix done"
