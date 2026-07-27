# `:dynamic-engine`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Role** — the engine host: wraps `:quickjs` (`DynamicEngine`) and serves host protocols to the
  remotely-updatable scraping JS (YouTube feed-shape changes ship without an app release).

- **Boot** — `DynamicEngine.create(client, appVersion, logger, engineJs = ENGINE_JS)` boots the whole `:engine-js`
  bundle (handlers wired BEFORE the eval via `QuickJsRuntime.create`'s configure hook, `versions` installed from C
  at create) and then health-checks it over the bundle-served `"init"` pipe (returns `backendVersion`, the bundle's
  self-declared revision; typed rejections `E20020` load / `E20021` health check).

- **Remote update / lifecycle** — `engineJs` is the remote-update seam; `DynamicEngineManager` owns the current
  engine:
  - `start(remoteJs?)` falls back to the baked-in baseline on a rejected remote bundle.
  - `swap(newJs)` flips new work onto the new engine first, then retires the old one (`"shutdown"` pipe = SIGINT:
    the bundle's `lifecycle.ts` clears timers + runs `onShutdown` callbacks; bounded `awaitIdle` drain; force-close
    at the deadline).

- **HTTP** (implemented) — a global `HttpRequest` builder over the `"http"` pipe command, served directly on a
  `kmphttp.KmpHttpClient` (streaming both ways; Android resolves `:kmpHttp:core`'s jvm variant, i.e. real OkHttp).

- **Error contract** (implemented) — JS `commandHandler(handler)` (handler gets `(src, out)`)/`JsEngineError` ↔ a
  per-command Kotlin method that `decodeEngineError`s the RESET into a typed `EngineError` (implements
  `kmpcommon.error.CodedError`) over structured RESET reasons (the typed `WireError` shape) — codes cross all tiers
  unchanged, classified at the origin (HOST_PROTOCOLS "Errors across the boundary").

- **Cookies** (implemented) — the bundle's `Cookies.get(url): Record<string,string>` / `.set(url, setCookie)` (one
  `Set-Cookie` string or an array) over the one-shot `"cookie/get"` / `"cookie/set"` commands.
  - Served on an **app-provided** `cookieJar` (the platform store the WebView player shares — Android
    `CookieManager`, desktop `CefCookieManager`; values parsed via `kmphttp.parseCookie`).
  - The engine's own HTTP client carries **NO jar** — the JS owns cookie POLICY: `logic/yt/cookies.ts` cooks every
    YouTube request's `Cookie` header from this store (CONSENT/SOCS synthesis — the legacy
    `YTHttpClientCookieStoreAndHeadersManager` role) and writes `Set-Cookie`s back, so cookie behavior ships in the
    remotely-updatable bundle and scraping + playback present one identity.
  - `NO_COOKIES` = capability inert; `E20040` bad head/url / `E20041` jar threw.

- **Cache** (implemented) — a **scoped** streaming keyed blob cache (every op takes a scope first — independent
  partitions, e.g. a 10-item home-feed scope + a 100MB download scope; `Cache.setJson`/`getJson`/`setText`/
  `getText`/`delete`/`list` + streaming `put`/`get` callbacks) over one-shot `"cache/get|put|delete|list"`, values
  streamed to/from disk, each entry host-stamped `size`/`updatedAt`/`lastUsed` (lastUsed bumped on get → LRU) + a
  caller `meta` string-map.
  - The store (`CacheStore`, shipped as `DiskCacheStore` — **metadata index in SQLite** (SqlDelight, so
    `list`/`init` is one indexed SELECT not a per-file dir scan) with **blobs on the okio `FileSystem`**,
    sha256(scope,key) filenames, atomic writes; the app passes a platform `SqlDriver` + blob dir) is **unbounded**.
  - **Eviction lives in the JS** — `scopedCache(scope, evictor)` drives a stateful `CacheEvictor` (`init(entries)`
    seeds from the scope, `onAdded(meta)` after every write→trim, `onRead(meta)` after every get refreshes
    lastUsed, `onDeleted(key)`), built-ins `keepCount(n)`/`lruCount(n)`/`lruSize(bytes)` or hand-written, so the
    policy is remotely updatable.
  - Wired via `DynamicEngine.create(cacheStore = …)` (absent → no cache; `E20050` bad head / `E20051` store threw).
  - Its home is the InnerTube session params (client version / `sts` / `visitorData` / bootstrap feed).

- **Embed page** — the WebView player's embed page also comes through the engine:
  `DynamicEngineManager.embedPage(userAgent)` → the bundle's `yt/embed` (engine-js `logic/yt/embed.ts`) fetches
  `/embed` on the cooked cookie identity with the WebView's UA **verbatim** and returns the rewritten HTML (CSP
  stripped, preloads defanged) carrying the `INJECT_JS_HERE` slot the player splices its boot script into — the
  splice point is remotely updatable, the `webview_ymusic.js` bundle stays an app asset.

- **Planned** — file / fs / sqlite.

- **Targets** — pure commonMain; targets = `:quickjs`'s (jvm/android/ios/macos/linux).

- **Protocol spec** — [`quickjs/HOST_PROTOCOLS.md`](../quickjs/HOST_PROTOCOLS.md).
