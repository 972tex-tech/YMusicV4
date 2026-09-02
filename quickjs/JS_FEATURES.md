# QuickJS JS feature support & performance guide

What JS you can (and should) write for the engine that runs the `:engine-js` bundle. The runtime is
**Bellard QuickJS** vendored at `native/quickjs` (commit `04be246`, `CONFIG_VERSION "2026-06-04"`),
core files only — **no `quickjs-libc`** (none of the `qjs` REPL's `std`/`os` globals exist) and
**no JIT**: everything is interpreted bytecode.

**Ground truth:** `src/commonTest/.../JsFeatureMatrixTest.kt` probes all of this against the real
runtime (62 live probes). On a vendor bump, run it and update this file together with the test —
the test fails first when support drifts.

## Supported — use freely

The language level is effectively **ES2024**. Everything below probes green:

- **Syntax**: optional chaining `?.`, nullish `??`, logical assignment `??=`/`||=`/`&&=`, numeric
  separators, `**`, spread/rest + destructuring, tagged templates, generators, async generators,
  `for await`, all class syntax — public/private fields, private methods, `#x in obj`, `static {}`
  blocks.
- **RegExp**: every current flag — named groups, lookbehind, `s` (dotAll), `y` (sticky), `d`
  (indices), and `v` (unicodeSets). (Slow, though — see below.)
- **Builtins**: `Promise.allSettled`/`any`/`withResolvers`; `Array.prototype.at`/`flat`/`flatMap`/
  `findLast`/`toSorted`/`toReversed`/`with`; `Object.hasOwn`/`fromEntries`/`groupBy` +
  `Map.groupBy`; `String.prototype.replaceAll`/`matchAll`/`isWellFormed`; `Error` `cause` +
  `AggregateError`; `globalThis`; `WeakRef`/`FinalizationRegistry`; `Proxy`/`Reflect`; `BigInt` +
  `BigInt64Array`; `Atomics`/`SharedArrayBuffer` (present but moot — there are no workers here,
  one engine = one thread). Native `Function.prototype.toString` is V8-shaped
  (`function name() { [native code] }`); `Error.stack` is V8-shaped and does not emit
  `eval`/`Function`/`anonymous` (those frames use `location.href`, or `about:blank`);
  `eval` of a non-string object whose `toString` is source
  (Chrome TrustedScript) runs that source. Native WHATWG `atob`/`btoa` (Latin-1 binary strings;
  url-safe `-`/`_` accepted) and `TextEncoder`/`TextDecoder` (UTF-8).

## NOT supported — the habit traps

These throw or come back `undefined`. Closure/tsc won't save you: they assume a browser/node lib.

| Missing | Use instead |
|---|---|
| **`Intl.*`** (the big one) | Nothing locale-aware exists. `toLocaleString` & friends exist but fall back to non-locale output. Localized labels must come from YouTube itself (`hl`/`gl` in the ctx) or the app. |
| `Temporal` | `Date` (present) + hand math. |
| `setTimeout` / `clearTimeout` / `queueMicrotask` | Not engine globals. Timers are host-served: the bundle's `transport.ts` provides them over the pipe. Microtask ordering: `Promise.resolve().then(fn)` (the player env also plants `queueMicrotask` when missing). |
| `URL` / `URLSearchParams` | Bundle `installNativeUrl()` (player) or hand-rolled parsing (`urlQuery` in `logic/yt/commands.ts`). |
| `fetch` / XHR / any DOM or web API | The bundle installs WHATWG `fetch` (plus `Headers`/`Request`/`Response`/`ReadableStream`/`AbortController`) as a wrapper of `HttpRequest` over the `"http"` pipe. XHR is the bundle stub. Other DOM APIs are whatever the bundle stubs (MSE, the PO snapshot env). |
| `structuredClone` | `JSON.parse(JSON.stringify(x))` or hand copy. |
| `using` (explicit resource management) | Parse error — mind your TS `target`; emit must not contain it. |
| `Array.fromAsync` | Manual `for await` accumulation. |
| ES modules / dynamic `import()` | The bundle is ONE eval'd IIFE script (esbuild-bundled); there is no module loader. |
| `console` | Not built-in — the bundle installs its own over `__consoleLog` (`console.ts`); fine to use *inside* the bundle, absent in raw eval'd scripts. |

## Supported but SLOW — measured here

No JIT means roughly 10–50× slower than V8 on compute-bound code; scraping/parsing logic is fine,
heavy transforms are not. Micro-timings from this repo's runtime (jvmTest, Intel x64 dev host,
`-O2` build, 5.3MB HTML-ish haystack — indicative, not benchmarks):

| Operation | Time | Faster alternative |
|---|---|---|
| Global regex scan, 60k matches over 5MB | 46ms | `indexOf` scan: **7ms (6.5× faster)** |
| `s += chunk` to 10MB | 111ms | `parts.push(chunk)` + `join("")`: **10ms (11× faster)** |
| `map().filter()` over 1M elements | 100ms | plain `for` loop: **38ms (2.6× faster)** |
| `JSON.parse` 4MB | 15ms | — (C implementation; already fast) |
| `JSON.stringify` 2MB out | 36ms | — |

Guidance, in the order it matters for this codebase:

- **RegExp is the engine's weakest area** (a backtracking interpreter with no optimizer and no
  literal prefiltering — it tries every position; V8 extracts a required literal and skips to
  candidates). Over multi-MB documents pin the regex to `indexOf` hits with engine-js's
  `anchoredExec(s, literal, stickyRe, offset)` (`logic/yt/scan.ts`): sticky (`/y`) means "match
  exactly here, zero scanning", and `offset` handles a fixed-width prefix before the literal.
  Measured on a 2.2MB decoy-heavy haystack: the 7-regex session scrape dropped from 118ms to 11ms
  (10.5×). There is **no backtracking timeout** — a catastrophic pattern hangs the engine thread,
  which is the module's "main thread" (AGENTS.md): every pipe, timer and command stalls with it.
  Keep patterns anchored and bounded.
- **Build big strings with `join` or stream them**: repeated `+=` reallocates; for pipe output,
  write chunks instead of materializing the whole string at all.
- **Higher-order array chains cost an interpreted call per element.** Irrelevant at feed size
  (dozens–hundreds of items) — keep the readable style there. For 100k+ element crunching, use
  plain loops, or better, don't do that in the bundle.
- **Lean on `JSON.parse`/`stringify`** — they are C. But multi-MB parses allocate big transient
  graphs, and GC runs on the engine thread: drop references promptly, don't retain whole InnerTube
  responses after extraction.
- **`BigInt` is software arithmetic** — much slower than `Number`; avoid unless a value genuinely
  exceeds 2^53.
- **`Proxy` pays a trap dispatch on every operation** — don't put it on hot paths.
- Build note: we compile QuickJS at `-O2` everywhere; upstream numbers show `-O3` gains ~7–10%
  interpreter throughput if that ever matters.
