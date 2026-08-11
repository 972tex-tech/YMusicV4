#!/usr/bin/env bash
# Build + bundle the native macOS player-lab .app and (optionally) launch it.
#   ./build.sh          build, bundle, and `open` the app
#   ./build.sh --no-run  build + bundle only
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
BUILD="$HERE/build"
FW_DIR="$ROOT/player/build/bin/macosX64/debugFramework"
APP="$BUILD/PlayerLab.app"
TARGET="x86_64-apple-macos12.0"

echo "==> Building Player.framework (macosX64)..."
(cd "$ROOT" && ./gradlew :player:linkDebugFrameworkMacosX64 --console=plain -q)
[ -d "$FW_DIR/Player.framework" ] || { echo "framework not found at $FW_DIR"; exit 1; }

# The WKWebView (YouTube) tab injects the built webview-js bundle; build it if missing.
ENGINE_JS="$ROOT/webview-js/dist/webview_ymusic.js"
if [ ! -f "$ENGINE_JS" ]; then
    echo "==> Building webview-js bundle..."
    (cd "$ROOT/webview-js" && { [ -d node_modules ] || npm install; } && npm run build:prod)
fi

echo "==> Compiling SwiftUI app..."
mkdir -p "$BUILD"
swiftc \
    -parse-as-library \
    -sdk "$(xcrun --show-sdk-path --sdk macosx)" \
    -target "$TARGET" \
    -F "$FW_DIR" \
    -framework Player \
    -Xlinker -rpath -Xlinker @executable_path/../Frameworks \
    -o "$BUILD/PlayerLab" \
    "$HERE/Sources/PlayerLab.swift"

echo "==> Assembling $APP..."
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Frameworks" "$APP/Contents/Resources"
cp "$BUILD/PlayerLab" "$APP/Contents/MacOS/PlayerLab"
cp -R "$FW_DIR/Player.framework" "$APP/Contents/Frameworks/"
cp "$ENGINE_JS" "$APP/Contents/Resources/webview_ymusic.js"

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleExecutable</key><string>PlayerLab</string>
    <key>CFBundleIdentifier</key><string>com.ymusic.playerlab</string>
    <key>CFBundleName</key><string>PlayerLab</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>1.0</string>
    <key>CFBundleVersion</key><string>1</string>
    <key>LSMinimumSystemVersion</key><string>12.0</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
</dict>
</plist>
PLIST

# Ad-hoc sign so the embedded framework loads cleanly.
codesign --force --deep --sign - "$APP" >/dev/null 2>&1 || true

echo "==> Built $APP"
if [ "${1:-}" != "--no-run" ]; then
    echo "==> Launching..."
    open "$APP"
fi
