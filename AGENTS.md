# AGENTS.md

This file provides guidance to AI coding agents working with code in this repository.

Machine-local dev notes are imported when present: @~/.agents/AGENTS.md

## Project Overview

YMusic V4 is a complete rewrite of `YMusicLegacy` (the previous YMusic Android app) on new tech stacks: Kotlin Multiplatform + Compose Multiplatform, targeting Android, desktop JVM, and iOS. The legacy sources are not published; if you have a copy it sits at `YMusicLegacy/` (gitignored) for reference.

## Modules

Each module has a one-line summary here; deeper detail lives in the module's own doc (linked). Read the detail doc before working inside that module.

**RULE — this section must not grow back.** This file loads into context on every prompt, module-relevant or not. When documenting module work, update the module's own `README.md` (create it if missing, link it here), never this list: a bullet stays ONE sentence — what the module is, where the detail lives. Anything longer belongs in the module doc.

- **`:kmpHttp`** — a port of the OkHttp API to Kotlin Multiplatform: OkHttp-shaped public API (`Request`/`Response`/`Headers`/…), coroutine-based instead of blocking, on JVM (typealias to real OkHttp), Apple (NSURLSession), and desktop-native (libcurl). Sub-modules: `:core`, `:curl`, `:json`, `:json-benchmark`. → [`kmpHttp/ARCHITECTURE.md`](kmpHttp/ARCHITECTURE.md)
- **`:kmpCommon`** — shared KMP primitives used across modules: `kmpcommon.uri.Uri` / `UriBuilder` (an RFC3986 URI type), and `kmpcommon.io.AsyncSource`/`AsyncSink` + `BufferedAsyncSource` (suspend-based okio-Buffer I/O, moved here from `:kmpHttp`; `:quickjs` adapts `Pipe` to them via `asAsyncSource()`/`asAsyncSink()`).
- **`:kmpFfmpeg`** — KMP wrapper over ffmpeg for metadata extraction / (future) transcoding. No-op on iOS (no ffmpeg there).
- **`:media-store`** — a media-metadata database (buckets / media items / playlists) on SQLite via SQLDelight; CRUD, queries, and reactive flows. Playlist membership is content-addressed (a `code` derived from `youtubeVid` or `fileSize`+duration), not row-FK'd. No scanning.
- **`:media-scanner`** — walks a document tree and reconciles it into `:media-store`, extracting metadata via `:kmpFfmpeg`. Storage-agnostic via a SAF-shaped `DocumentsProvider` (Android SAF, desktop okio filesystem, iOS no-op). Concurrent and cancelable.
- **`:engine-js`** *(directory gitignored — not yet published)* — the one home for ALL engine JS (pipe transport, protocol layers, scraping logic, test fixtures): strict TypeScript → esbuild → Closure ADVANCED, emitting `engine.js` (the prod baseline, remotely replaceable whole) and `engine-test.js` (prod + pipe-driven fixtures).
- **`webview-js`** *(directory gitignored — not yet published; not a Gradle module — a standalone npm/TS package at the repo root)* — the injected content script for the WebView YouTube player: evaled at document-start on the embed page, rewrites YouTube's own player from inside (host bridge, SABR handling, ad skip, audio-only, signature decode); built standalone to ES5 via esbuild → babel → terser.
- **`:dynamic-engine`** — the engine host: `DynamicEngine` over `:quickjs` + `DynamicEngineManager` (the remote-update seam: start/swap/retire), serving the host protocols (http, cookies, cache, settings) to the remotely-updatable scraping bundle with a typed cross-tier error contract. → [`dynamic-engine/README.md`](dynamic-engine/README.md), protocol spec [`quickjs/HOST_PROTOCOLS.md`](quickjs/HOST_PROTOCOLS.md)
- **`:quickjs`** — our own QuickJS binding (vendored Bellard QuickJS): a duplex flow-controlled pipe transport (JNI on jvm/android, cinterop on Apple/linux) under a multiplexed `Pipe` layer in commonMain (`QuickJsRuntime`), streaming native JSON, bytecode precompile; ships NO JS (the `:engine-js` bundle is evaled once at create). → [`quickjs/README.md`](quickjs/README.md)
- **`:player`** — the cross-platform playback layer: narrow `MediaEngine` SPI + `Player` facade, all tunables in one `PlaybackConfig` (audio-only = quality `"zero"`), one shared video container each engine owns its surface inside; backends AVFoundation / ExoPlayer / VLCJ local + the WebView YouTube engine. → [`player/README.md`](player/README.md)
- **`:yt-media`** — pure-Kotlin googlevideo SABR/UMP protocol + the download logic and player cache built on it; knows nothing about WebView/JS/player — everything per video is fed in as plain data (`SabrStreamSpec`). → [`yt-media/README.md`](yt-media/README.md)
- **`:download`** — the offline Download Manager: task queue + store over `:media-store`, SABR downloads via `:yt-media`, ffmpeg mux via `:kmpFfmpeg` (audio-only stays raw m4a, no ffmpeg).
- **`:image-loader`** — our own simple Compose image loader: everything is a fetcher (network on DiskLruCache, file, custom schemes), view-size scaled decode, memory LRU + optional scaled disk cache, LIFO concurrency gate, `AsyncImage`. → [`image-loader/README.md`](image-loader/README.md)
- **`:commonUi`** — the shared Compose Multiplatform app UI (drawer nav, Discover feed/search, Library, mini-player + Now Playing, settings) over `AppGraph`; localized en/vi/ar via compose-resources — NEVER hardcode UI text. → [`commonUi/README.md`](commonUi/README.md)
- **`:desktopApp`** — the JVM desktop product shell (`./gradlew :desktopApp:run`): JCEF + VLCJ engines, file-backed media store + scanner; `:desktopApp:webviewProbe` is the headless WebView-playback diagnostic. → [`desktopApp/README.md`](desktopApp/README.md)
- **`:androidApp`** — the Android product shell (plain AGP 9 application module) booting the shared `App` over ExoPlayer + Android WebView engines. → [`androidApp/README.md`](androidApp/README.md)
- **`iosApp`** *(not a Gradle module — the Xcode shell)* — the iOS product shell: SwiftUI `@main` hosting the same shared Compose `App` from the `:commonUi` framework, over AVFoundation local + WKWebView YouTube engines. → [`iosApp/README.md`](iosApp/README.md)
- **`:settings`** — typed key-value `SettingsStore` (StateFlow reads, per-key distinct), in-memory + JVM Properties-file impls, `AppSettings` descriptors (hl/gl/theme accent/uiLanguage — hl/gl is InnerTube content language/region, uiLanguage is the app chrome; kept independent). Android persistence = the shell's SharedPreferences-backed store (`androidApp/PrefsSettingsStore`).
- **`:playground`** — a Compose Multiplatform demo app to hand-check `:player` (desktop JVM only); the native Apple checks are the SwiftUI spikes `spikes/macos-player-lab/` and `spikes/ios-player-lab/`. → [`playground/README.md`](playground/README.md)

## Coding Rules

### Code style
- **Kotlin**: official Kotlin style (4-space indent), enforced by ktlint via Spotless — run `./gradlew spotlessApply` after Kotlin changes; `spotlessCheck` must pass. Line length is not enforced; aim for ~120.
- **TS** (`engine-js/src/`): TypeScript (strict + `noUncheckedIndexedAccess`/`noPropertyAccessFromIndexSignature`/`exactOptionalPropertyTypes`), prettier-formatted (`.prettierrc`, printWidth 100, `quoteProps: preserve`; enforced by the pre-commit hook) — `npx prettier@3 --write <file>`. The bundle follows a **statically-analyzable discipline** (required by the Closure ADVANCED pass):
  - **Structure is dotted, data is bracketed/quoted.** Declared interfaces/classes: dotted access, literal property names only — never `obj[expr]` on a structured type; ADVANCED renames them all consistently. `Record`/map collections, parsed JSON, and every wire object: bracket access / quoted literal keys (Closure never renames quoted; dotted here = renamed = corrupted wire). The compiler enforces reads via `noPropertyAccessFromIndexSignature`; writes are on the author.
  - **Every boundary shape is declared exactly once** — wire JSON ↔ Kotlin (`wire.ts`, `WireError`), C natives (`natives.d.ts`), globals (`types.d.ts`). Nothing ad-hoc crosses a boundary.
  - **No reflection over structure**: no `Object.keys`/`for-in` driving logic on interface-typed objects; `JSON.parse`/`stringify` touch declared wire shapes only. No `eval`/`Function`, no prototype patching.
- **C** (`quickjs/native/*.c/.h`): 4-space indent, K&R braces; match the existing files. Never reformat `quickjs/native/quickjs/` (vendored) or `YMusicLegacy/`.

### Error Handling
- **Never catch and silently ignore exceptions.** A caught exception must be propagated somewhere the caller can see it — rethrown, returned, or surfaced in a result (e.g. collected into a `ScanError`/errors list), never reduced to a bare counter or swallowed. The only exception is deliberate, documented fire-and-forget cleanup (e.g. `closeQuietly()`).
- **Propagation exists so the user gets a meaningful message when a task fails.** Meaningful is exactly one of two shapes:
  - a **well-known category** the user can act on (Network error, Disk full, …) — classified where the failure is understood, rendered as a localized message at the UI;
  - an **error code** for everything technical: "Something went wrong (E12375)". Never show technical detail to the user ("HTTP protocol frame malformed" can't be localized and only confuses) — the detail stays in the exception message and logs; only the code goes on screen, for the user to report.
- **Error codes**: `E` + 5 digits, a unique hardcoded literal at exactly one throw/classification site — a user report is answered by a global search for the code. Mint via [`error-codes.txt`](error-codes.txt) — the ledger, one row per code `E##### <flag> <explanation>`: grep it for a free number, add a `-` row in the same commit as the throw site. The flag is `D` (deployed = shipped in a release → burned forever, never reuse/renumber/remove) or `-` (not deployed → free to remove, renumber, or reallocate). On release, flip every `-` row of the shipped module to `D`. Nothing is deployed yet, so all codes are currently `-` (free); a removed non-shipped throw site just deletes its row. Backstop for a forgotten flip: `git log --all -S "E12345"`. Ranges by module: E1xxxx `:quickjs`, E2xxxx `:dynamic-engine` (incl. its remote JS), E3xxxx `:kmpHttp`, E4xxxx `:kmpFfmpeg`, E5xxxx `:media-store` / `:media-scanner`, E6xxxx `:player`.
- **Two channels, opposite directions.** Across a boundary (Kotlin ↔ JS ↔ server), an error has two independent flows: the **value goes up** (host → caller) — the `{code, userMessage?}` the *user* sees, minted once at the origin, wrapped-never-re-minted; the **logging context goes down** (caller → host) — a `List<String>` scope trail the *developer* sees, consumed only when something is logged. They never mix: nothing logged is ever shown to the user, and the code never carries developer detail.
- **Log at the birth site, with context.** A failure is logged **where it is born**, in the frame that caught it, with full native fidelity (the real `Throwable`) — a stringified error round-tripped across a boundary and rebuilt has lost its stack. To make a birth-site log meaningful the caller's context is threaded *down* to it as a scope trail (the boundary severs the stack, so the host frame can't see the caller): the JS side tags a call with the trail, the host protocol appends its own hop and logs any failure with it through the app's `kmpcommon.log.Logger` (see `:dynamic-engine`'s `HttpProtocol` + `HttpRequest.logContext` → the `logCtx` head field). Seed element 0 with a per-job id so the birth-site breadcrumb and the caller's one job-edge crash record join. Birth-site logs are **breadcrumbs/warnings**, not crash records — a failure the caller recovers from must not count against crash-free rate.

### Coroutines
- **Suspend functions must be safe to call from the main thread.** Use `withContext(Dispatchers.IO)` for I/O or `withContext(Dispatchers.Default)` for CPU-bound work.
- **Don't fake async.** A purely-blocking function (SQLite, files) whose body is `withContext(Dispatchers.X) { … }` with no real suspend point inside should NOT be `suspend` — that just advertises "safe from main" while hopping a dispatcher the caller could hop itself. Declare it a plain `fun` annotated `@WorkerThread` (in `kmpcommon`) and let the caller — already off the main thread — own threading. Reserve `suspend` for genuine suspend points (network, channels, async `Source` reads). A `suspend` fn may freely call these blocking ones. Reference: `MediaBlobCache` / `BlobIndex` / `SegmentIndex` in `:yt-media` are non-suspend `@WorkerThread`; their SABR-session / proxy callers are what suspend.
- **The QuickJS engine thread is a main thread — treat it exactly like the UI one.** Everything on a `DynamicEngine`/`QuickJsRuntime` dispatcher shares ONE thread with all JS execution, every pipe and every timer: host-protocol `onAccept` handlers run there, and `create`/`healthCheck` run on it by contract. Any I/O or potentially-blocking platform call inside that code (cookie store, SQLite, SharedPreferences, files) hops off with a plain `withContext(Dispatchers.IO) { … }` — one blocking call on the engine thread wedges every command silently (the "yt commands hang pre-HTTP" zombie). Pipe `send`/`receive` are fine from anywhere; they hop internally. This is a settled decision: handlers deliberately run ON the engine dispatcher (re-dispatching them to a pool was considered and rejected — it would introduce real handler concurrency and thread-safety risk); hop off per call instead.
- **Mutex is NOT reentrant** — nested acquisition deadlocks. Guard specific state only, keep critical sections small, no nested lock calls. For complex cases use `limitedParallelism` or an actor/channel pattern.
  ```kotlin
  class SafeState<T>(initial: T) {
      private var value = initial
      private val mutex = Mutex()
      fun get() = value  // non-blocking read
      suspend fun update(block: (T) -> T) = mutex.withLock { value = block(value) }
  }
  ```

### I/O
- **Choose carefully between ByteArray and okio Source/Sink — streams by default, ByteArray only when a platform API forces it.** A `ByteArray` in a signature means the whole payload sits in the heap at once; a `Source`/`Sink` bounds memory to a segment and composes (file, network, cache entry, adapter). Legitimate ByteArray sites carry a justification comment naming the forcing API (e.g. Skia's bytes-only codec). This applies to interfaces most of all — an interface typed `ByteArray` forces every implementation to buffer.

### Common mistakes
- Don't use `Char.isLetterOrDigit` to check for `[a-zA-Z0-9]` — it includes non-ASCII letters like `'β'.isLetterOrDigit() == true`.

### Comments
**Default to no comment.** The code shows *what* it does; a comment earns its place only when it states something the code *cannot* — a genuine **hack**, or a **non-obvious constraint/invariant** the next reader would otherwise miss. When unsure, delete it.
- **The story goes in the commit message, never the code.** Why the old code was wrong, what it used to do, what you tried instead — that is commit-message material, and pure noise the moment the PR merges (it explains a defect the next reader never saw, not the code as it stands). Banned in code: "used to…", "this would…", "instead of…", "previously…", before/after descriptions, or any reference to the change itself.
- **A comment that restates the name or signature is narration too** — `writeBytes` → "write bytes" adds nothing the reader can't see. Cut it.
- **Survivors are short** — one line stating the constraint, not a paragraph.
- **A refactor deletes narration, it doesn't carry it** — re-apply this rule to every line you move or touch; never preserve an old verbose style just because it was already there.

### Git / commits
- **Never push. The user pushes.** Agents commit locally only — no `git push`, no force-push, ever, even when the push seems like the natural completion of a requested merge or rewrite. State the push command(s) for the user to run instead.
- **Never make one big commit that bundles everything.** Split a batch into the smallest still-meaningful units and commit them separately, in dependency order — e.g. a toolchain/dependency bump (Kotlin/Gradle/AGP) is its own commit, separate from standalone refactors, feature work, and tests. Use path-scoped `git add <paths>` (or `git add -p` when one file's hunks belong to different units). Keep each commit building. When asked to "commit", default to this rather than a single sweep.
