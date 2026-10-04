# webview-js

YouTube embed content-script (host bridge, SABR, ads, signature decode). Built standalone to ES5.
Android still copies `dist/webview_ymusic.js` as an app asset for the player-cache gate.

Migrated from `ymix-server/clientSrc` (the `webview_ymusic` entry and its closure).

## Layout

- `src/webview_ymusic.ts` — entry IIFE: builds `YMixWebViewApi`, installs `window.ymixWvApi`, hooks
  script loading, runs `ytScripts`.
- `src/webview_api.ts` — the host bridge (`postMsg`/`reportError`/script transform/player-response cache).
- `src/yt/` — the YouTube player mods (`yt_script.ts` wires them together).
- `src/wv/` — WebView-level glue (script-load interception, internal log buffer).
- `src/protobuf/` — minimal protobuf codec for the SABR / streaming payloads.
- `src/globals.d.ts` — ambient host globals + `Window` augmentations.

## Build

Standalone npm module (not a Gradle project). Node required.

```
npm install
npm run typecheck   # tsc --noEmit — the type gate
npm run build       # dev  → dist/webview_ymusic.js (inline sourcemap, DEBUG=true)
npm run build:prod  # prod → minified, DEBUG=false
npm run watch       # rebuild on change
```

Pipeline (`build.mjs`), mirroring the source project (ymix-server): **esbuild** bundles TS + resolves
modules + inlines defines (no transpile) → **babel** `@babel/preset-env` downlevels to **ES5**
(`{ chrome: 37 }`) → **terser** minifies (ES5, `safari10`, prod only). tsc (`npm run typecheck`) is the
type gate. Compile-time globals via esbuild `--define`: `DEBUG`, `FORCE_RE_MISMATCH_CRASH`,
`BUILD_DATE`. `ymusicSessionId` is a **runtime** global the host installs before the script — not a
define.

**Why ES5, not esbuild-native:** the script is injected into whatever WebView the device has, so it
targets the oldest browser reasonably possible. esbuild cannot emit below ES2015 (it refuses to lower
block-scoping/classes/destructuring), so babel does the ES5 downlevel. No `core-js` — the only
runtime APIs used are `Map`/`Set` (Chrome 38) and an iterable spread babel lowers to an
`Array.from`-based helper (Chrome 45), so the effective **run floor is ~Chrome 45** even though the
ES5 syntax parses far below that (same as ymix-server ships). Change `BROWSER_TARGET` in `build.mjs`
to move the floor; add `core-js` polyfills if you ever need to run below Chrome 38.

## Testing in a desktop browser (no app)

The engine is just a content script driving YouTube's own player through
`window.ymusicAction(...)`. You can exercise the whole thing — including intermittent failures like
the ~43 s SABR stop seen on-device — in **desktop Chrome, with no Android/iOS/desktop host**, by
serving the embed page through a local proxy that reproduces the two things the native host
normally does: inject the built bundle **at document-start** (first `<script>` in `<head>`) and
**strip CSP** so the injected script and its script-evals are allowed to run.

**`test/inject.py`** is a mitmproxy addon that does exactly that: it strips CSP headers on all
`youtube.com` responses, rewrites `www.youtube.com/embed/*` HTML to inject a prelude (the runtime
globals the host provides — `ymusicSessionId`, `ymixInitConfig`, `isAndroid`, `mainAppVer`, … — plus
a `ymixChannelAndroid` stub that routes every JS→native event to `console.log`) followed by the
bundle, and serves the bundle same-origin from a sentinel path (re-read per request, so
`npm run watch` rebuilds are picked up live).

```
brew install mitmproxy          # or: pipx install mitmproxy
npm run build                   # produce dist/webview_ymusic.js first
mitmdump -s test/inject.py      # proxy on 127.0.0.1:8080
```

A ready-made, **muted** driver is `test/run.sh <videoId> <seconds>` — it launches a throwaway
Chrome (`--proxy-server` + `--ignore-certificate-errors` + `--mute-audio`) over this proxy, calls
`ymusicAction` load via CDP, and streams every `[ymix]` event with timestamps (needs
`pip install pychrome`). Tests are muted by default — always keep them so.

For an interactive session instead: point a browser at the proxy (with the mitmproxy CA trusted —
visit `http://mitm.it` through the proxy once to install it), open
`https://www.youtube.com/embed/<videoId>`. The bundle is already injected and connected; in the
console:

```js
ymusicAction(JSON.stringify({ what: 0, videoId: '<id>', playWhenReady: true, volume: 1 }))
```

JS→native events print as `[ymix] <sessionId>_<event> <payloadJson>` — watch `state_changed`,
`progress`, `quality_changed`, and `report_error` to reproduce and inspect a failure (e.g. let it
play and watch for the state change / error around the 43 s mark).

**Automated all-command sweep.** `test/run_commands.sh [videoId] [mobile|desktop]` drives every `ymusicAction` command (all 16 `what` codes, default mobile-emulated) through the bundle and asserts each: return value, the `[ymix]` events it produces, and a player-state readback where one exists (volume, playback rate, seek position, load duration, play/pause intent, po_token, player_res, scale class), plus the error contract (bad params / unknown action -> `error`). Verified 21/21 on both mobile and desktop. Player-preference commands whose only effect is an async player-driven event (quality/captions/audio-track) are asserted on their `ok` dispatch, with the event reported informationally. Muted throughout.

**Action `what` codes** (`ACTION_*` in `src/yt/player_connect.ts`): `0` Load · `1` Preload ·
`2` SetPlayWhenReady · `3` SetVideoQuality · `4` Seek · `5` Stop · `6` SetLoop ·
`7` SetCaptionLanguage · `8` SetAudioTrack · `9` SetCaptionVisible · `10` SetVolume ·
`11` SetSpeed(speed,pitch) · `12` Bulk(actions[]) · `13` AddWantedPlayerRes · `14` SeekToLiveHead ·
`15` SetScaleMode · `16` GetPoToken.

This is a **Chrome-MCP-drivable** loop: navigate the browser to the embed URL through the proxy,
then evaluate `ymusicAction(...)` and read the console. Caveats: desktop Chrome is not the WebView
the device ships, so SABR/PO-token/background-audio behavior can differ — it reproduces script logic
and bridge/state flow, not device-exact playback. Uses `www.youtube.com/embed` (not
`youtube-nocookie.com`); widen the host match in `test/inject.py` if you need the latter.
