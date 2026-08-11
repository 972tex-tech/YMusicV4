#!/bin/bash
# Builds two simulator .app variants of the spike:
#   BgAudioSpike      (com.ymusic.bgspike)      - UIBackgroundModes: audio
#   BgAudioSpikeNoBG  (com.ymusic.bgspike.nobg) - no background modes
set -euo pipefail
cd "$(dirname "$0")"

ARCH=$(uname -m)
[ "$ARCH" = "arm64" ] && ARCH=arm64 || ARCH=x86_64

python3 tools/gen_audio.py build/audio

build_variant() { # NAME BUNDLE_ID URL_SCHEME BG_MODES_XML
    local NAME=$1 BID=$2 SCHEME=$3 BG=$4
    local APP=build/$NAME.app
    rm -rf "$APP"
    mkdir -p "$APP"
    xcrun -sdk iphonesimulator swiftc \
        -target "$ARCH-apple-ios15.0-simulator" -O \
        -o "$APP/$NAME" src/main.swift
    sed -e "s|__EXEC__|$NAME|" \
        -e "s|__BUNDLE_ID__|$BID|" \
        -e "s|__NAME__|$NAME|" \
        -e "s|__URL_SCHEME__|$SCHEME|" \
        -e "s|__BG_MODES__|$BG|" \
        src/Info.plist.template > "$APP/Info.plist"
    cp web/index.html build/audio/*.wav "$APP/"
    codesign --force --sign - "$APP" 2>/dev/null
    echo "built $APP"
}

BGXML='<key>UIBackgroundModes</key><array><string>audio</string></array>'
build_variant BgAudioSpike com.ymusic.bgspike bgspike "$BGXML"
build_variant BgAudioSpikeNoBG com.ymusic.bgspike.nobg bgspike2 ""
