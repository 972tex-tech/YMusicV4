# `:engine-js`

Module detail for the one-line summary in the root [`AGENTS.md`](../AGENTS.md) — read this before working inside the module.

- **Scope** — the one home for ALL engine JS (pipe transport, protocol layers, scraping logic, test fixtures),
  TypeScript with two ambient contracts (`types.d.ts` = the app-facing surface, `natives.d.ts` = the C FFI boundary).

- **Build pipeline** — tsc strict (the type checker) → esbuild (bundle only, `--target=esnext` — lowering breaks
  Closure's property renaming) → **Closure Compiler ADVANCED** (minify + full mangling, identifiers AND property
  names; type-based passes off — erased-types input).

- **Artifacts** — two, both ADVANCED:
  - `engine.js` — production; ships with the app as the baseline, remotely replaceable as a whole.
  - `engine-test.js` — production + pipe-driven test fixtures; `:quickjs`/`:dynamic-engine` tests and pipe_stress
    run on it.

- **Unmangled names** — the ONLY ones:
  - `externs.js` (the C natives, the `__js*` transport globals, `versions`, `console`).
  - QUOTED wire keys — all wire objects are written quoted/bracketed; `.prettierrc` has `quoteProps: preserve`
    because prettier's default strips them.

- **Tests** — drive the engine via pipes only — never externally-evaled JS calling API symbols (they're mangled);
  the few remaining `load()` snippets touch externed natives only.

- **Tooling** — via `npx` (Node at build; versions pinned in buildSrc).

- **Runtime limits** — what JS the QuickJS engine supports and what's slow (no Intl/URL/fetch/
  timers-from-engine; regex & `+=` are hot-path traps): [`../quickjs/JS_FEATURES.md`](../quickjs/JS_FEATURES.md).

- **`BACKEND_VERSION`** — the manually-bumped bundle revision, in build.gradle.kts; an esbuild `--define`
  compile-time const — `declare const`, inlined, never a runtime global.

- **Versioning / backward compat** — the host installs the frozen, non-writable `versions` global from C (`{app}`:
  the app version identifies the whole host surface — transport, protocols, app ship as one build) before the
  bundle — **backward-compat branching inside the JS** (`versions.app >= 123` / `typeof __x === "function"`),
  not a load handshake: natives may be added AND removed per app version; the always-latest bundle encodes the
  compatibility matrix.
