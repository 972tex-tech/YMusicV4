# `:engine-js`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Scope** — the one home for ALL engine JS (pipe transport, protocol layers, scraping logic, MSE stubs, player commands, test fixtures),
  TypeScript with two ambient contracts (`types.d.ts` = the app-facing surface, `natives.d.ts` = the C FFI boundary).

- **Build pipeline** — tsc strict (the type checker) → esbuild (bundle only, `--target=esnext` — lowering breaks
  Closure's property renaming) → **Closure Compiler ADVANCED** (minify + full mangling, identifiers AND property
  names; type-based passes off — erased-types input).

- **Artifacts** — all ADVANCED:
  - `engine.js` — scrape (feed/search/login/download); remotely replaceable.
  - `player.js` — MSE YouTube player; separate QuickJS runtime.
  - `engine-test.js` — both + pipe fixtures for `:quickjs`/`:dynamic-engine` tests.

- **Unmangled names** — the ONLY ones:
  - `externs.js` (the C natives, the `__js*` transport globals, `versions`, `console`, WHATWG `fetch` /
    `Headers` / `Request` / `Response` / `ReadableStream` / `AbortController`).
  - QUOTED wire keys — all wire objects are written quoted/bracketed; `.prettierrc` has `quoteProps: preserve`
    because prettier's default strips them.

- **Tests** — drive the engine via pipes only — never externally-evaled JS calling API symbols (they're mangled);
  the few remaining `load()` snippets touch externed natives only.

- **Tooling** — via `npx` (Node at build; versions pinned in buildSrc).

- **Runtime limits** — what JS the QuickJS engine supports and what's slow (no Intl/URL/
  timers-from-engine; regex & `+=` are hot-path traps). Engine builtins include native `atob`/`btoa`
  and `TextEncoder`/`TextDecoder`. WHATWG `fetch` is the bundle wrapping `HttpRequest`:
  [`../quickjs/JS_FEATURES.md`](../quickjs/JS_FEATURES.md).

- **`BACKEND_VERSION`** — the manually-bumped bundle revision, in build.gradle.kts; an esbuild `--define`
  compile-time const — `declare const`, inlined, never a runtime global.

- **PRODUCTION** — esbuild `--define` from `-Pproduction` (same bake as Kotlin
  `BuildFlags.PRODUCTION`, default false). Chatty logs use `debug(function () { return concat; })`
  so the thunk is not called when true. `console.info` / `warn` always reach the file. There is
  no `MSE_DEBUG` / `SABR_DEBUG` / `BG_DUMP` / `DEBUG_LOGS` define.

- **Versioning / backward compat** — the host installs the frozen, non-writable `versions` global from C (`{app}`:
  the app version identifies the whole host surface — transport, protocols, app ship as one build) before the
  bundle — **backward-compat branching inside the JS** (`versions.app >= 123` / `typeof __x === "function"`),
  not a load handshake: natives may be added AND removed per app version; the always-latest bundle encodes the
  compatibility matrix.

- **Headless WEB player** — `logic/player/` (raw `orig/env.raw.js` + PO minter `pot.ts` + `boot.ts`). Homepage
  `get_player_config` (live EVENT_ID + `ytAtN` program) → minter on `HttpRequest` → OWL slot → eval `base.js`
  (`fetch`) → KEVLAR_WATCH `Application.create` / `loadVideoById`. Native `:mse` via `protocol/mse.ts`.
  The snapshot env must fill Chrome's 5 PDF `navigator.plugins` (empty `{length:5}` fat-snapshots and no `webPo`).
  Player ads (`/youtubei/v1/player`) and SABR (`/videoplayback`) rewrite as `HttpRequest.addInterceptor`; fetch/XHR only build the request.
