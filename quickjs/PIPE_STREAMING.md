# :quickjs — pipe protocol + native JSON parse (design)

Two things only. Higher-level protocols (HTTP envelopes, etc.) are the app's job, built on these.

## 1. Pipe = a socket-style byte stream

Bidirectional byte stream between Kotlin and JS. One side `openPipe(name)`, the other `onAccept(name)`;
`name` is a pure routing name — arguments are streamed as bytes, not encoded in the name.

A `Pipe` is the **pair of ends** this side holds, like `pipe(2)` fds — each closed independently,
no fused close:
```kotlin
class Pipe(val source: PipeSource, val sink: PipeSink, val command: String) // destructurable
interface PipeSource {
    suspend fun receive(sink: Buffer, byteCount: Long): Long // okio Source.read shape; -1 at EOF; throws on RESET
    suspend fun close()                                      // abandon reading: peer sends EPIPE
}
interface PipeSink {
    suspend fun send(source: Buffer, byteCount: Long)        // okio Sink.write shape; suspends on backpressure
    suspend fun close(reason: String? = null)                // END, or RESET(reason)
}
```
The Kotlin surface speaks **okio `Buffer`** end to end: `receive` moves already-buffered bytes into
the sink as whole segments (no copy) and, when parked, hands deliver() the sink itself — the
writer's bytes are copied straight into its segments, sized at delivery time so a full chunk can
rendezvous; `send` walks the source's segments, pinning each across the FFI (zero-copy)
and consuming it only after the peer fully accepted it. `PipeSource.asAsyncSource()` /
`PipeSink.asAsyncSink()` (pure delegation) adapt an end to `kmpcommon.io.AsyncSource`/`AsyncSink`,
so `BufferedAsyncSource`'s `readUtf8LineStrict`/`indexOf`/`readDecimalLong` work over a pipe
directly — the adapters are views and do not own the end (their `close()` is a no-op).
```js
await pipe.sink.send(u8, offset, len)      // undefined when fully accepted; a Promise under backpressure
await pipe.source.receive(u8, offset, len) // -> bytes read (<= len), or -1 at EOF; throws the RESET reason
pipe.sink.close(reason?); pipe.source.close()
```
Both are **await-optional**: the fast path returns synchronously (`send` -> undefined, `receive` ->
a number when bytes are already buffered), a Promise only under real backpressure/parking — hot
loops can check (`typeof r === "number" ? r : await r`) to skip the microtask hop QuickJS charges
for awaiting a non-thenable.

Each direction is a **bounded, flow-controlled byte stream** (64 KB cap, 8 KB pool segments,
32 KB low-water), exactly like a kernel pipe:
- **Partial writes + DRAIN.** The FFI `write` is synchronous and returns the **accepted** byte
  count: what a parked reader took plus what fit in the buffer. `accepted < len` parks the writer
  (Kotlin `send` suspends; the JS send promise stays pending) until the reader drains the buffer to
  the low-water mark and the transport fires a **drain**, which resumes the write from where
  it stopped. `-1` = the reader closed its read end → `BrokenPipeException` / EPIPE rejection.
- **Buffers live on the reader's side.** Bytes JS writes land in an okio `Buffer` in Kotlin (its
  segment pool — no per-chunk allocation; Kotlin `receive` never crosses the FFI). Bytes Kotlin
  writes land in a **C-owned `PipeBuf`** (`quickjs_pipe.c`): a chain of pooled 8 KB segments plus
  the pending read job — host writes never enter JS at all (`qjs_pipe_write` memcpys into the
  segments and advances the job in C), and no per-chunk JS object exists anywhere.
- **One copy per chunk.** A write crosses as the writer's own bytes (host → JS: the ByteArray
  pinned for the memcpy; JS → host: a direct ByteBuffer / raw pointer over the typed array, valid
  only during the call) and is copied once into the reader-side buffer. String/JSON reads then
  decode **straight out of the C segments** (still one copy total); only a raw `receive(dst)`
  copies out a second time. Consequence: **the send buffer must stay unmodified until the send
  completes** (a partial write holds a window into it).
- **Closing the sink, two flavors**: `sink.close()` = END — the peer's `receive` returns -1 once
  drained. `sink.close(reason)` = **RESET** — the peer's pending data is discarded and its
  parked/next `receive` **throws** the reason (Kotlin: `PipeResetException`; JS: rejection), so a
  mid-stream failure or cancellation is never mistaken for a clean EOF. A handler that throws
  RESETs its pipe with the failure message automatically. `source.close()` abandons the read side:
  the peer's next send gets EPIPE.
- **One receiver / one sender**: only one read and one write may be outstanding per pipe; a
  concurrent second call fails fast rather than queueing (overlapping calls would race the
  cursors, so that's always a bug). On the JS sink this is enforced, not a convention: while an
  async write/flush/close is in flight the pipe is claimed, and any other sink op throws
  `concurrent write on the same pipe` instead of interleaving into the staging segment. The one
  exception is `close(reason)` — a RESET is an **abort** and always wins: no flush (the peer
  discards buffered data on reset anyway), allowed mid-write, and a parked send is failed
  (`send aborted: <reason>`) rather than left hanging.
- **Aborts close the pipe (Kotlin).** A `send`/`receive` that exits abnormally — cancellation
  included — leaves the stream at an unknowable position (a cancelled parked send has already
  delivered a prefix; a cancelled receive can race a delivery into its abandoned sink), so it
  closes that side on the way out: an aborted send RESETs (`"send aborted mid-stream: …"`), an
  aborted receive closes the read end (peer gets EPIPE). Without this, a cancelled 200 KB send
  measurably left the peer 64 KB of truncated stream with `error=none` and spliced the next
  message into the middle of it. One rule, one predictable state — like NIO's interruptible
  channels, an interrupted op kills the channel.

**Transport (no framing).** The FFI boundary is a handful of operations per direction — nothing
allocates a per-frame header; commands and reasons cross as native strings:
- host → JS: `NativeEngine.open(id, cmd) -> 0 | -1 refused` → `__jsOpen`;
  `write(id, buf, off, len) -> accepted` → **C only** (`qjs_pipe_write`, no JS on the data path);
  `closeWrite(id, reason?)` → `qjs_pipe_close_write` (delivers EOF/RESET to the C buffer) +
  `__jsClose(id)` (JS-side reap bookkeeping); `closeRead(id)` → `__jsCloseRead(id)`;
  `drain(id)` → `__jsDrain(id)`.
- JS → host: `__hostOpen -> 0 | -1 refused` / `__hostData(id, u8, off, len) -> accepted` /
  `__hostClose(id, reason)` / `__hostCloseRead(id)`
  → `EngineCallbacks.onOpen/onWrite/onCloseWrite/onCloseRead` (JNI statics on jvm/android; one
  cinterop trampoline with an op arg on native). The inbound DRAIN fires from the C pipe layer
  through a shim-registered hook → `onDrain`.
`id` is an argument (Kotlin opens odd ids, JS even). `open` is request/response, C-error-code
style: opening a command nothing serves **fails at the open itself** (like `ECONNREFUSED` — Kotlin:
`QuickJsException`, JS: throw), leaving no pipe on either side. Routing lives in the Kotlin mux and
C's id → `PipeBuf` registry (which holds each registered buffer alive, so a host write can never
touch a freed one); the JS mux routes only the outbound events.

**JNI critical-region discipline** (jvm/android): `write` holds the ByteArray with
`GetPrimitiveArrayCritical` while `qjs_pipe_write` copies it. No JS executes on that path —
settling a parked read's promise only *schedules* reaction jobs, and the pump runs after the
release — so JNI re-entry is impossible by construction. The DRAIN hook fires exclusively from
JS-initiated read paths (during a write the host writer is mid-write, not parked, so the accepted
count itself is the flow control); the shim keeps its in-critical guards as cheap defense.

Small JS helpers on `ymix`: `open`, `onAccept` (replaces same-name handlers; returns
`{ remove() }`, a no-op once replaced — the Kotlin `onAccept` mirrors this with an
`AcceptRegistration`), `utf8Encode` (QuickJS has no TextEncoder; a single
string delegates to native `__utf8Encode`, several parts measure with `__utf8Length` and fill with
`__utf8Into`), `stringifyJson` (native `__stringifyJson`: object → UTF-8 JSON bytes),
`utf8Decode(bytes, from?, to?)` (native `JS_NewStringLen` — no interpreted loop). There is
**no separate `buffered(pipe)` wrapper**: each end is itself the okio-`Buffer`-style object.
`pipe.source` carries the raw `receive` plus buffered reads
(`readInt`/`readLong`/`readUtf8Line`/`readUtf8`/`readBytes`/`readJsonObject`/`readJsonArray`, §2) and its
`close()`; `pipe.sink` carries `send` plus buffered writes (`writeInt`/`writeLong`/`writeBytes`/
`writeUtf8`/`writeUtf8Range`/`writeUtf8Line`/`putByte`/`writeObjectAsJson`, `flush`) and `close(reason?)` —
the write-staging segment is allocated lazily, so a raw or read-only pipe pays nothing.

Every read is a **thin native over the C `PipeBuf`** (`__pbRecv`/`__pbInt`/`__pbLong`/`__pbLine`/
`__pbJson`/`__pbUtf8`/`__pbBytes`): it returns the result when it is already buffered, else it parks the job
(a `JS_NewPromiseCapability` the write path settles) and returns the Promise — await-optional by
construction, with zero JS between the host's bytes and the scanner. The scanners run **in C over
the segment chain** and *consume as they feed* (DRAIN always fires): `readJson*` drives the
streaming JSON parser (§2, via its C-level `qjs_jstream_*` API), `readUtf8Line` memchr-scans —
a line completing within one segment decodes straight from it, a spanning line accumulates in the
buffer's DynBuf — so a line or body longer than the pipe cap grows only its own accumulator
(memory = the value itself) and can never deadlock the parked writer, by construction rather than
by special case. `readUtf8` / `readBytes` — the whole-body materializers — accumulate the same way;
utf8 decodes once at EOF, bytes steals the accumulator into a tight `Uint8Array`. Consumed-exactly
semantics mean a head line + JSON body + tail share one stream position for free.

The sink is a **fixed 8 KB segment**, not a growing buffer, so sink memory stays ~one segment no
matter how much is written:
- `writeUtf8`/`writeUtf8Range` decide fit up front from the `(to - from) * 3` UTF-16 worst case:
  a string that fits coalesces into the segment via `__utf8Into` (an `encodeInto` straight from
  flat string storage — the vendored patch — no allocation), one too big for a whole segment is
  encoded ONCE into an exact-size C buffer (`__utf8Encode`, a zero-copy `Uint8Array`) and streamed
  through `send()` — one crossing per drain round instead of one flush per segment-full. Variadic:
  `writeUtf8(a, b)` avoids the string concat.
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
skip the hop: `var p = pipe.sink.writeUtf8(s); if (p) await p;`. `writeObjectAsJson` serializes through
the **resumable native encoder** (§2): the walk lives in C as explicit state, `__jsonEncFill` fills
the segment per call, and the ~10-line JS pump only carries backpressure. Measured (min of 3) on a
305 KB value: 431 ms (the old async JS walker) → **4 ms**, vs 6 ms for the whole-document
`__stringifyJson` + `writeBytes`; at 1.2 MB: 10 ms vs 15 ms — the streaming encoder now **beats the
one-shot at every size** (the one-shot materializes the document three times: the JSON JSString,
its UTF-8 conversion, and the Uint8Array copy; the encoder materializes nothing). A plain
`writeUtf8` of the same 305 KB string: 82 ms → 3 ms (`__utf8Into`/`__utf8Encode`). The backpressure
story is unchanged: bounded buffer + parked writer, so a stalled consumer stops the producer
instead of queueing unbounded bytes anywhere.

## 2. Native JSON <-> bytes: streaming parse + resumable encode

`quickjs_json.c` (shared by the JNI and cinterop shims). The core is a **streaming JSON parser**
written as a resumable state machine — its whole reason to exist is to parse a body arriving in
chunks **without ever holding the whole body in memory**. (There is deliberately *no* struct filter:
it was a lot of machinery for a payload-tree saving that's marginal next to not buffering the raw
bytes, which is what the streaming parser already gives us. Filter/reshape the parsed value in JS.)

### Streaming parse (the primary path) — `source.readJsonObject/readJsonArray()`

JSON reading is a method on the pipe, so one pipe reads a status line, headers, *and* the JSON body
over the same inbound queue, sharing one buffer:
```js
const status = await pipe.source.readUtf8Line();   // e.g. read some framing first...
const data   = await pipe.source.readJsonObject(); // ...then parse the JSON body
const tail   = await pipe.source.readUtf8();       // ...and keep reading whatever follows it
```

Two readers — `readJsonObject()` and `readJsonArray()`, one per container type. Each validates the
root type on its **first byte** and throws immediately if it's the wrong type (a scalar, the other
container, or e.g. an HTML error page starting with `<`), rather than parsing a whole unexpected
value first. A feed body is always one or the other, so there's no untyped reader. (Native:
`__jsonNew(expect)` with `expect` 1=object / 2=array; the check fires once, on the root's first
non-ws byte.)

Both drive the parser through its C-level API (`qjs_jstream_new/push/done/end` — the pipe's
`PipeBuf` feeds segments to it without any JS in between). The same parser is also exposed to JS
as `__jsonNew`/`__jsonPush`/`__jsonDone`/`__jsonEnd` (an opaque JS class; freed on GC or after
end), used directly by tests:
```js
const p = __jsonNew(expect);                   // opaque parser
const consumed = __jsonPush(p, buf, off, len); // feed a chunk -> bytes consumed (see tail note)
if (__jsonDone(p)) return __jsonEnd(p);        // value complete -> value; throws on malformed input
```

Why push, not pull: frames are delivered into JS by the **same single engine thread** that would
block waiting for them, so a pull-parser deadlocks. `__jsonPush` feeds bytes to a state machine
that suspends mid-token at each chunk boundary. **Memory held at any instant** = the parse stack
(bounded by nesting depth) + the current partial token + the output value tree — never the raw
chunk bytes, which are consumed and discarded. That's the saver for multi-MB responses: we build
the value without ever holding the whole byte buffer (nor the double-copy of materializing first).

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
materializing the whole JSON text. `sink.writeObjectAsJson(value)` drives it: each
`__jsonEncFill` fills the sink's 8 KB segment at C speed, and the JS pump flushes between fills
(backpressure). The encoder object is a JS class — GC frees it, so abandoning one mid-emit is safe
(same finalizer + `gc_mark` rules as the parser; it holds the frame containers and the pending
value). Matches `JSON.stringify` (toJSON, key order, escapes, NaN/undefined rules) with two
deliberate divergences: a root `undefined` emits `null`, and a **cycle throws TypeError** (the
explicit stack makes the ancestry check cheap). Because the walk resumes across fills, `toJSON()`/
getters run *during* serialization — a value mutated under backpressure serializes as visited.

String tokens escape + UTF-8-encode in one pass **straight out of JSString storage** (Latin-1 or
UTF-16, ropes linearized first) via `JS_ToFlatString`/`JS_GetFlatStringStorage` — a ~25-line patch
appended to the vendored `quickjs.c` (`BEGIN patch` / `END patch`; re-apply when upgrading QuickJS).
No per-token cstring materialization; hot atoms (`toJSON`, `length`) are interned once per encoder;
int32 numbers format directly. Net: faster than the native one-shot with one-segment memory.

### `__stringifyJson(value) -> Uint8Array` — the one-shot encode

Native `JS_JSONStringify` + native UTF-16->UTF-8 (`JS_ToCStringLen`). Throws `TypeError` if the
value has no JSON form.

```js
pipe.sink.send(__stringifyJson(payload));  // value -> bytes
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
- Parse-before-download / pull+skip reader (parse a value before the rest of the body arrives),
  modeled on `:kmpHttp:json`'s `JsonReader`.
