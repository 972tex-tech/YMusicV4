#!/bin/sh
# Compiles YMusic.icon (Icon Composer) into Assets.car beside it. Needs Xcode 26's actool; the
# result is committed so packaging works on any Xcode.
set -eu
cd "$(dirname "$0")"
out=$(mktemp -d)
trap 'rm -rf "$out"' EXIT
xcrun actool YMusic.icon --compile "$out" --platform macosx --minimum-deployment-target 11.0 \
    --app-icon YMusic --output-partial-info-plist "$out/partial.plist" >/dev/null
cp "$out/Assets.car" Assets.car
