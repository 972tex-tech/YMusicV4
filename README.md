# YMusic

A Kotlin Multiplatform music player for Android, desktop (MacOs, Windows, Linux), and iOS/iPad.

> [!CAUTION]
> **Only install YMusic from trusted sources:** [ymusic.io](https://ymusic.io) or [GitHub Releases](https://github.com/ymusic-io/YMusicV4/releases). Anyone can build a modified copy — bad actors can ship a fake app that steals your Google account credentials when you sign in.
>
> **Misuse Disclaimer:**
> The authors are not responsible for any misuse of the application.

## Running

Prerequisites: **JDK 17+**, **Node.js**, **CMake + a C toolchain** (QuickJS native library),
**Android SDK** for the Android app, **Xcode** (macOS) for the iOS app.

FFmpeg comes from [ffmpeg-build](https://github.com/ymusic-io/ffmpeg-build), checked out next to
this repository (`../ffmpeg-build`) and built locally — nothing is downloaded. Build the targets
you need there first (see its README): `scripts/build-desktop.sh` for the desktop app,
`scripts/build-android.sh <abi>` for `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64` for Android,
`scripts/build-ios.sh` for iOS. A missing build fails with the command that produces it.

```sh
# one-time
git clone --recursive --shallow-submodules https://github.com/ymusic-io/ffmpeg-build ../ffmpeg-build

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
