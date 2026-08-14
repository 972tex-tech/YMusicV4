# YMusic

A Kotlin Multiplatform music player for Android, desktop (JVM), and iOS.

## Running

Prerequisites: **JDK 17+**, **Node.js**, **CMake + a C toolchain** (QuickJS native library),
**Android SDK** for the Android app, **Xcode** (macOS) for the iOS app.

```sh
# one-time
(cd webview-js && npm install && npm run build)

# desktop app
./gradlew :desktopApp:run

# android
./gradlew :androidApp:installDebug
```

The iOS app builds from `iosApp/iosApp.xcodeproj` in Xcode; its Kotlin framework comes from
`:iosAppKotlin` automatically.

## License

YMusic is open source under the [GNU General Public License v3.0](LICENSE), except the
foundation libraries below, which are Apache-2.0 so any project can reuse them:

| Directory | License |
|---|---|
| `kmpCommon/` | [Apache-2.0](kmpCommon/LICENSE) |
| `kmpHttp/` | [Apache-2.0](kmpHttp/LICENSE) |
| everything else | [GPL-3.0](LICENSE) |

Nothing under `kmpCommon/` or `kmpHttp/` may depend on a GPL module.

By contributing, you agree that your contribution is licensed under the same license as the
directory it modifies.
