# :quickjs — pipe protocol + native JSON parse (design)

Two things only. Higher-level protocols (HTTP envelopes, etc.) are the app's job, built on these.

## 1. Pipe = a socket-style byte stream

Bidirectional byte stream between Kotlin and JS. One side `openPipe(name)`, the other `onAccept(name)`;
`name` is a pure routing name — arguments are streamed as bytes, not encoded in the name.

```kotlin
interface Pipe {
    val command: String
    suspend fun send(buffer: ByteArray, offset: Int, len: Int)     // suspends on backpressure
    suspend fun send(bytes: ByteArray)                              // convenience
    suspend fun receive(buffer: ByteArray, offset: Int, len: Int): Int  // -1 at EOF; throws on RESET
    suspend fun close(reason: String? = null)                      // half-close: END, or RESET(reason)
    suspend fun closeRead()                                        // abandon reading: peer sends EPIPE
}
```
```js
await pipe.send(u8, offset, len)      // undefined when fully accepted; a Promise under backpressure
await pipe.receive(u8, offset, len)   // -> bytes read (<= len), or -1 at EOF; throws the RESET reason
pipe.close(reason?); pipe.closeRead()
```

Each direction is a **bounded, flow-controlled byte stream** (64 KB cap, 8 KB pool segments,
32 KB low-water), exactly like a kernel pipe:
- **Partial writes + DRAIN.** The FFI `write` is synchronous and returns the **accepted** byte
  count: what a parked reader took plus what fit in the buffer. `accepted < len` parks the writer
  (Kotlin `send` suspends; the JS send promise stays pending) until the reader drains the buffer to
  the low-water mark and the transport fires a **drain**, which resumes the write from where
  it stopped. `-1` = the reader closed its read end → `BrokenPipeException` / EPIPE rejection.
- **Buffers live on the reader's side, in the reader's language.** Bytes JS writes land in an okio
  `Buffer` in Kotlin (its segment pool — no per-chunk allocation; Kotlin `receive` never crosses
  the FFI). Bytes Kotlin writes land in the JS mux's queue of `Uint8Array` views over **pooled 8 KB
  C segments** (`__segTake`/`__segRelease`) — the queue is views + counters; the bytes stay in C
  memory, never copied into the JS heap.
- **Zero-copy crossing, rendezvous-first.** A write crosses as a one-shot view of the writer's own
  bytes (host → JS: an ArrayBuffer wrapping the pinned ByteArray, **detached** when the call
  returns; JS → host: a direct ByteBuffer / raw pointer over the typed array, valid only during the
  call). If a reader is parked, the bytes are copied **straight into its buffer** — one copy, the
  unavoidable heap-to-heap transfer. Only the remainder is copied once into pool segments (buffered
  path = 2 copies total, 0 allocations at steady state). Consequence: **the send buffer must stay
  unmodified until the send completes** (a partial write holds a window into it).
- **Half-close, two flavors**: `close(null)` = END — the peer's `receive` returns -1 once drained.
  `close(reason)` = **RESET** — the peer's pending data is discarded and its parked/next `receive`
  **throws** the reason (Kotlin: `PipeResetException`; JS: rejection), so a mid-stream failure or
  cancellation is never mistaken for a clean EOF. A handler that throws RESETs its pipe with the
  failure message automatically. `closeRead()` abandons the read side: the peer's next send gets
  EPIPE.
- **One receiver / one sender**: only one `receive()` and one `send()` may be outstanding per pipe;
  a concurrent second call fails fast rather than queueing (overlapping calls would race the
  cursors, so that's always a bug).

**Transport (no framing).** The FFI boundary is a handful of operations per direction — nothing
allocates a per-frame header; commands and reasons cross as native strings:
- host → JS: `NativeEngine.open(id, cmd)` / `write(id, buf, off, len) -> accepted` /
  `closeWrite(id, reason?)` / `closeRead(id)` / `drain(id)`
  → JS globals `__jsOpen` / `__jsData(id, view) -> accepted` / `__jsClose(id, reason)` /
  `__jsCloseRead(id)` / `__jsDrain(id)`.
- JS → host: `__hostOpen` / `__hostData(id, u8, off, len) -> accepted` / `__hostClose(id, reason)` /
  `__hostCloseRead(id)` / `__hostDrain(id)`
  → `EngineCallbacks.onOpen/onWrite/onCloseWrite/onCloseRead/onDrain` (JNI statics on jvm/android;
  one cinterop trampoline with an op arg on native).
`id` is an argument (Kotlin opens odd ids, JS even). All routing lives in the Kotlin + JS mux —
C only shuttles operations and owns the segment pool (`quickjs_pipe.c`).

**JNI critical-region discipline** (jvm/android): `write` holds the ByteArray with
`GetPrimitiveArrayCritical` while `__jsData` runs, so that synchronous JS path must never re-enter
JNI — the bootstrap's `_data` only calls pure-C natives (`__segTake`), promise resolutions merely
*schedule* microtasks, and the pump runs after the release. The shim enforces it: under the
critical flag `__hostDrain` is queued (flushed after release) and any other `__host*` call throws.
Drains are therefore emitted from *read* paths only — which is also why `_data` itself never needs
one.

Small JS helpers on `ymix`: `open`, `onAccept`, `utf8Encode` (QuickJS has no TextEncoder; small
inputs), `utf8Decode(bytes, from?, to?)` (native `JS_NewStringLen` — no interpreted loop),
`readAll(pipe)` (drain a stream to one `Uint8Array`, single copy), and `buffered(pipe)` — an
okio-style wrapper with a buffered source (`read`/`readUtf8Line`/`readUtf8`/`readByteArray`, plus
`readJsonObject`/`readJsonArray`, §2) and a buffered sink (`writeBytes`/`writeUtf8`/
`writeUtf8Range`/`writeUtf8Line`/`writeObjectAsJson`, `flush`, `close(reason?)`).

The buffered **source has no window of its own**: the pipe's inbound queue of segment views *is*
the buffer, and both scanners are **consuming push-parsers fed segment by segment** (the same
`New/Push/Done/End` shape): `readJson*` drives the streaming JSON parser (§2), and `readUtf8Line`
drives the native **line splitter** (`__lineNew/Push/Done/End` in `quickjs_pipe.c`) — memchr per
push, a line completing within one segment decodes straight from C memory, a spanning line
accumulates inside the splitter. Because bytes are *consumed as fed* (DRAIN always fires), a line
longer than the pipe cap simply grows the splitter's accumulator — memory = the line itself — and
can never deadlock the parked writer, by construction rather than by special case. `readByteArray`
transfers the segment views and assembles the result with a single copy. Consumed-exactly semantics
mean a head line + JSON body + tail share one stream position for free.

The sink is a **fixed 8 KB segment**, not a growing buffer, so sink memory stays ~one segment no
matter how much is written:
- `writeUtf8`/`writeUtf8Range` encode via **one native call per string** (`__utf8Into`,
  `JS_ToCStringLen` straight into the segment — no per-codepoint interpreted loop; ~30× faster).
  Variadic: `writeUtf8(a, b)` avoids the string concat. A string token bigger than a whole segment
  is encoded natively once (`__utf8Bytes`) and streamed through the `writeBytes` bypass — memory =
  one encoded copy of that single token, never of the document.
- `writeObjectAsJson` runs the **resumable native JSON encoder** (`__jsonEncNew`/`Fill`/`Done`,
  §2): one native call fills the whole segment, the pump flushes between fills.
- `writeBytes` **bypasses the segment for large inputs** (`>=` one segment): it flushes what's
  pending, then streams the input straight to the pipe in segment-sized slices — no copy into the
  sink buffer.
- Small writes coalesce in the segment and flush when it fills or on an explicit `flush()`/`close()`.
  Flush boundaries are invisible to the peer — the pipe is a byte stream and the reader reassembles
  across them — so auto-flush is a memory bound, never a framing change.

Sink writes are **plain functions, never `async`**: they return `undefined` when completed
synchronously (the common case — the write only filled the segment) and a Promise only under real
backpressure (a flush hit a full pipe), resuming via an internal continuation. Rationale: QuickJS
charges a full microtask round-trip (~0.4 µs) for `await` even on a non-thenable, plus a promise
allocation per `async` call — per-token awaits made `writeObjectAsJson` ~70× slower than the native
one-shot. `await` still works on every write (awaiting `undefined` is legal), but hot loops can
skip the hop: `var p = io.writeUtf8(s); if (p) await p;`. `writeObjectAsJson` serializes through
the **resumable native encoder** (§2): the walk lives in C as explicit state, `__jsonEncFill` fills
the segment per call, and the ~10-line JS pump only carries backpressure. Measured (min of 3) on a
305 KB value: 431 ms (the old async JS walker) → **4 ms**, vs 6 ms for the whole-document
`__stringifyJson` + `writeBytes`; at 1.2 MB: 10 ms vs 15 ms — the streaming encoder now **beats the
one-shot at every size** (the one-shot materializes the document three times: the JSON JSString,
its UTF-8 conversion, and the Uint8Array copy; the encoder materializes nothing). A plain
`writeUtf8` of the same 305 KB string: 82 ms → 3 ms (`__utf8Into`/`__utf8Bytes`). The backpressure
story is unchanged: bounded buffer + parked writer, so a stalled consumer stops the producer
instead of queueing unbounded bytes anywhere.

## 2. Native JSON <-> bytes: streaming parse + resumable encode

`quickjs_json.c` (shared by the JNI and cinterop shims). The core is a **streaming JSON parser**
written as a resumable state machine — its whole reason to exist is to parse a body arriving in
chunks **without ever holding the whole body in memory**. (There is deliberately *no* struct filter:
it was a lot of machinery for a payload-tree saving that's marginal next to not buffering the raw
bytes, which is what the streaming parser already gives us. Filter/reshape the parsed value in JS.)

### Streaming parse (the primary path) — `bufferedPipe.readJsonObject/readJsonArray()`

JSON reading lives on the buffered pipe wrapper (`ymix.buffered(pipe)`), so one wrapper reads a
status line, headers, *and* the JSON body over the same pipe, sharing one buffer:
```js
const io = ymix.buffered(pipe);
const status = await io.readUtf8Line();   // e.g. read some framing first...
const data   = await io.readJsonObject(); // ...then parse the JSON body
const tail   = await io.readUtf8();       // ...and keep reading whatever follows it
```

Two readers — `readJsonObject()` and `readJsonArray()`, one per container type. Each validates the
root type on its **first byte** and throws immediately if it's the wrong type (a scalar, the other
container, or e.g. an HTML error page starting with `<`), rather than parsing a whole unexpected
value first. A feed body is always one or the other, so there's no untyped reader. (Native:
`__jsonNew(expect)` with `expect` 1=object / 2=array; the check fires once, on the root's first
non-ws byte.)

Both drive four native primitives:
```js
const p = __jsonNew(expect);                   // opaque parser (a JS class; freed on GC or after end)
const consumed = __jsonPush(p, buf, off, len); // feed a chunk -> bytes consumed (see tail note)
if (__jsonDone(p)) return __jsonEnd(p);        // value complete -> value; throws on malformed input
```

Why push, not pull: frames are delivered into JS by the **same single engine thread** that would
block waiting for them, so a pull-parser deadlocks. `__jsonPush` feeds bytes to a state machine
that suspends mid-token at each chunk boundary. **Memory held at any instant** = the parse stack
(bounded by nesting depth) + the current partial token + the output value tree — never the raw
chunk bytes, which are consumed and discarded. That's the saver for multi-MB responses: we build
the value without ever holding the whole byte buffer (nor the double-copy a `readAll` would do).

**Tail data (`{json}tail`).** `__jsonPush` parses at most ONE top-level value: it stops the instant
the value completes and returns how many bytes it consumed. A return < `len` means the value is
done and the rest of the chunk is unrelated trailing data — the reader advances the buffer by
exactly `consumed`, so the tail stays queued for the next `readUtf8Line`/`read`/etc. (This
is why the parser reports a consumed count instead of demanding it swallow the whole input.)

`__parseJson(uint8array)` is a one-shot for when you already have all the bytes — QuickJS's own
`JS_ParseJSON` (fast, strict, any top-level value).

### Resumable encode — `__jsonEncNew(value)` / `__jsonEncFill(enc, dst, off)` / `__jsonEncDone(enc)`

The stringify mirror of the streaming parser: a depth-first walk kept as explicit C state (frame
stack + one in-flight token), so a value serializes into **fixed-size output chunks** without ever
materializing the whole JSON text. `bufferedPipe.writeObjectAsJson(value)` drives it: each
`__jsonEncFill` fills the sink's 8 KB segment at C speed, and the JS pump flushes between fills
(backpressure). The encoder object is a JS class — GC frees it, so abandoning one mid-emit is safe
(same finalizer + `gc_mark` rules as the parser; it holds the frame containers and the pending
value). Matches `JSON.stringify` (toJSON, key order, escapes, NaN/undefined rules) with two
deliberate divergences: a root `undefined` emits `null`, and a **cycle throws TypeError** (the
explicit stack makes the ancestry check cheap). Because the walk resumes across fills, `toJSON()`/
getters run *during* serialization — a value mutated under backpressure serializes as visited.

String tokens escape + UTF-8-encode in one pass **straight out of JSString storage** (Latin-1 or
UTF-16, ropes linearized first) via `JS_ToFlatString`/`JS_GetFlatStringStorage` — a ~25-line patch
appended to the vendored `quickjs.c` (marked "YMusic patch"; **re-apply when upgrading QuickJS**).
No per-token cstring materialization; hot atoms (`toJSON`, `length`) are interned once per encoder;
int32 numbers format directly. Net: faster than the native one-shot with one-segment memory.

### `__stringifyJson(value) -> Uint8Array` — the one-shot encode

Native `JS_JSONStringify` + native UTF-16->UTF-8 (`JS_ToCStringLen`). Never route a big payload
through the interpreted `ymix.utf8Encode` (per-codepoint JS loop). Throws `TypeError` if the value
has no JSON form.

```js
pipe.send(__stringifyJson(payload));  // value -> bytes
```

**Gotchas:**
- A class that holds refcounted JSValues (the streaming parser holds half-built containers + the
  result) MUST provide `gc_mark` as well as a finalizer — else the cycle collector / shutdown sweep
  mis-accounts them and a debug build asserts `list_empty(&rt->gc_obj_list)` at `JS_FreeRuntime`.
  (JVM's CMake build defines `NDEBUG` and hides this; the native static lib keeps asserts on, so
  `macosX64Test` is the one that catches it.)
- `JS_ParseJSON`'s tokenizer relies on a trailing `\0` sentinel, but a typed array isn't
  NUL-terminated — so the one-shot `__parseJson` copies into a `len+1` NUL-terminated buffer before
  parsing. (The streaming parser tracks `[ptr, end)` and needs no sentinel.)
- `js_typed_array_constructor` reads `argv[1]`/`argv[2]` unconditionally (counting on interpreter
  undefined-padding), so C callers of `JS_NewTypedArray` must pass an explicitly padded
  `{ab, JS_UNDEFINED, JS_UNDEFINED}` — argc=1 with a lone stack value reads garbage stack memory.

## Deferred (only if profiling demands)
- Native frame header (avoid the send-side frame-build copy).
- Body-bytes-bypass-JS fast path (Kotlin -> C buffer -> parser, never a JS Uint8Array per chunk).
- Parse-before-download / pull+skip reader (parse a value before the rest of the body arrives),
  modeled on `:kmpHttp:json`'s `JsonReader`.
