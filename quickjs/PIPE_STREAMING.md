# :quickjs — pipe protocol + native JSON parse (design)

Two things only. Higher-level protocols (HTTP envelopes, etc.) are the app's job, built on these.

## 1. Pipe = a socket-style byte stream

Bidirectional byte stream between Kotlin and JS. One side `openPipe(name)`, the other `onAccept(name)`;
`name` is a pure routing name — arguments are streamed as bytes, not encoded in the name.

```kotlin
interface Pipe {
    val command: String
    suspend fun send(buffer: ByteArray, offset: Int, len: Int)     // copy-first
    suspend fun send(bytes: ByteArray)                              // convenience
    suspend fun receive(buffer: ByteArray, offset: Int, len: Int): Int  // inbox -> buffer; -1 at EOF
    suspend fun close()                                            // half-close: done sending
}
```
```js
pipe.send(u8, offset, len)
await pipe.receive(u8, offset, len)   // -> bytes read (<= len), or -1 at EOF
pipe.close()
```

- **Ownership**: `send` is copy-first (the transport copies `buffer[offset,+len)` synchronously
  before returning) → reuse the buffer after you await it. `receive` copies inbox → your buffer.
- **Cross-side is safe**: JS and Kotlin have separate heaps; the FFI copies at the boundary.
- **Half-close**: `close` signals "done sending" (peer's `receive` returns -1 once drained); you can
  still receive, so request/response over one pipe works.
- **Inbox**: arrived payloads queue and drain through `(head, headPos)` — no per-`receive` alloc.
- **One receiver**: only one `receive()` may be outstanding per pipe. A concurrent second call
  fails fast (Kotlin: `IllegalStateException`; JS: the promise rejects) rather than queueing —
  overlapping receives would race `(head, headPos)`, so that's always a bug.

**Transport (no framing).** The FFI boundary is *not* one framed byte blob; it is three operations
per direction, so nothing allocates a per-frame header and command/reason cross as native strings:
- host → JS: `NativeEngine.open(id, command)` / `data(id, buf, off, len)` / `closePipe(id, reason)`
  → JS globals `__jsOpen(id, cmd)` / `__jsData(id, arraybuffer)` / `__jsClose(id, reason)`.
- JS → host: `__hostOpen(id, cmd)` / `__hostData(id, u8, off, len)` / `__hostClose(id, reason)`
  → `EngineCallbacks.onOpen/onData/onClose` (JNI statics on jvm/android; one cinterop trampoline
  with an op arg on native).
`id` is an argument (Kotlin opens odd ids, JS even). `close`'s `reason` is `null` for a normal
half-close (END) or a string for an error (RESET); the receiving side ends inbound either way. All
routing (which pipe, streaming) lives in the Kotlin + JS mux — C only shuttles the operation.

Small JS helpers on `ymix`: `open`, `onAccept`, `utf8Encode`/`utf8Decode` (QuickJS has no
TextEncoder), `readAll(pipe)` (drain a stream to one `Uint8Array`), and `buffered(pipe, sinkFlush?)`
— an okio-style wrapper with a buffered source (`read`/`readUtf8Line`/`readUtf8`, plus
`readJsonObject`/`readJsonArray`, §2) and a buffered sink (`writeUtf8`/`writeUtf8Line`, `flush`,
`close`). Sink writes just accumulate in a buffer until an explicit `flush()` (or `close()`) sends
them — the caller decides when to flush. The whole sink is **synchronous** (`send` is copy-first,
so there's nothing to await): `writeUtf8`/`writeUtf8Line`/`flush`/`close` are all sync. Only the
source `read*` methods are async (they can suspend on `receive`).

## 2. Native JSON <-> bytes: streaming parse + `__stringifyJson`

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
exactly `consumed`, so the tail stays in the window for the next `readUtf8Line`/`read`/etc. (This
is why the parser reports a consumed count instead of demanding it swallow the whole input.)

`__parseJson(uint8array)` is a one-shot for when you already have all the bytes — QuickJS's own
`JS_ParseJSON` (fast, strict, any top-level value).

### `__stringifyJson(value) -> Uint8Array` — the encode mirror

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
