// The pipe transport's JS half + host-scheduled timers, built on the transport entry points
// (__jsOpen/__jsClose/__jsCloseRead/__jsDrain in; __hostOpen/__hostData/__hostClose/__hostCloseRead
// out) and the C pipe buffer (__pb*, see quickjs/native/quickjs_pipe.h and natives.d.ts). The first
// module of the single engine bundle (src/index.ts) — QuickJsRuntime.create evals that bundle once
// at creation and the native engine captures the __js* globals defined here right after.
// Implements the ymix/Pipe contract declared in types.d.ts. pipe_stress evals the built bundle.
//
//  - ymix.open(command) -> a pipe to Kotlin (this side is the client).
//  - ymix.onAccept(command, async (pipe) => ...) -> handle pipes Kotlin opens to us (server
//    side); returns { remove() } to unregister.
//  - ymix.onEvent(name, fn) -> host DynamicEngine.fire opens `"event"` (UTF-8 name, END).
//  - a pipe is the PAIR OF ENDS this side holds, like pipe(2) fds: pipe.source (readable) and
//    pipe.sink (writable), each closed independently — closing both releases the pipe.
//    source: receive(buf,off,len) (-> count or -1), buffered reads (readInt/readLong, readUtf8,
//    readBytes, readUtf8Line, readJsonObject/readJsonArray), close() (host's next send gets EPIPE).
//    sink: send(buf,off,len), buffered writes coalescing through a lazily-allocated staging
//    segment (writeInt/writeLong, writeBytes, putByte, writeUtf8[Range|Line], writeBytesAsBase64url, writeObjectAsJson, flush()),
//    close() = flush + END, close(reason) = RESET abort. Plus pipe.command / pipe.id.
// Kotlin opens odd pipe ids, JS opens even ones, so the two id spaces never collide.
//
// MEMORY MODEL (see PIPE_STREAMING.md): the whole inbound direction lives in C — host writes go
// straight into pooled C segments (never through JS), and every read is a thin native that
// returns the result when it is already buffered or a Promise that C settles on a later write /
// EOF / RESET. Hot callers can skip the microtask hop with
// `const r = pipe.source.readX(); typeof r === "..." ? r : await r`. Only the OUTBOUND half is JS:
// the staging segment, the partial-send park, and the DRAIN/EPIPE bookkeeping below.

/** A pipe as the transport routes it: the public ends plus the host->us entry points. */
interface TransportPipe extends Pipe {
  /** Host closed its write end (C already delivered EOF/RESET to the buffer). */
  _end(): void;
  /** A refused ymix.open: drop the C registration without touching the host (no pipe there). */
  _abandon(): void;
  /** Host abandoned its read end: our sends are EPIPE from now on. */
  _peerCloseRead(): void;
  /** Flow control from the host: its buffer drained; continue the parked partial send. */
  _drain(): void;
}

/** The parked partial send: resumed slice by slice on DRAIN. */
interface Writer {
  buffer: Uint8Array;
  pos: number;
  end: number;
  resolve: () => void;
  reject: (e: Error) => void;
}

/* Capture C pipe natives once; snapshot code may delete them from globalThis. */
const nHostOpen = __hostOpen;
const nHostData = __hostData;
const nHostClose = __hostClose;
const nHostCloseRead = __hostCloseRead;
const nPbNew = __pbNew;
const nPbCloseRead = __pbCloseRead;
const nPbRecv = __pbRecv;
const nPbInt = __pbInt;
const nPbLong = __pbLong;
const nPbLine = __pbLine;
const nPbJson = __pbJson;
const nPbUtf8 = __pbUtf8;
const nPbBytes = __pbBytes;
const nUtf8Encode = __utf8Encode;
const nUtf8Into = __utf8Into;
const nUtf8Length = __utf8Length;
const nUtf8Decode = __utf8Decode;
const nStringifyJson = __stringifyJson;
const nB64urlInto = __b64urlInto;
const nJsonEncNew = __jsonEncNew;
const nJsonEncFill = __jsonEncFill;
const nJsonEncDone = __jsonEncDone;

// Encode one or more strings to UTF-8, concatenated. A single string is the native one-shot;
// several parts measure + fill in C (__utf8Length / __utf8Into) so QuickJS never concatenates
// the strings and never walks code points in JS.
function utf8Encode(...parts: string[]): Uint8Array {
  if (parts.length <= 1) {
    const s = parts.length === 1 ? parts[0]! : "";
    return nUtf8Encode(s, 0, s.length);
  }
  let n = 0;
  for (let i = 0; i < parts.length; i++) {
    const s = parts[i]!;
    n += nUtf8Length(s, 0, s.length);
  }
  const buf = new Uint8Array(n);
  let off = 0;
  for (let i = 0; i < parts.length; i++) {
    const s = parts[i]!;
    off += nUtf8Into(s, 0, s.length, buf, off);
  }
  return buf;
}

/** JSON.stringify semantics to UTF-8 bytes in C. Throws if the value has no JSON representation. */
function stringifyJson(value: unknown): Uint8Array {
  return nStringifyJson(value);
}

const pipes: Record<number, TransportPipe> = {};
const accepts: Record<string, (pipe: Pipe) => void | Promise<void>> = {};
let nextId = 2; // JS opens even ids; Kotlin opens odd.
/** Keeps the `EPIPE` prefix callers match on; the suffix names the pipe for logs. */
function brokenPipe(id: number, command: string): Error {
  return new Error("EPIPE: broken pipe [#" + id + " " + command + "]");
}

function makePipe(id: number, command: string): TransportPipe {
  const pb = nPbNew(id); // the C-owned inbound buffer; reads delegate to it
  let writeEnded = false,
    readClosed = false; // routing bookkeeping only — buffer state is C's
  // outbound: writer = the parked partial send {buffer, pos, end, resolve, reject}.
  let peerReadClosed = false,
    weEnded = false,
    writer: Writer | null = null;
  // buffered-WRITE staging: a fixed 8 KB segment coalescing small writes, allocated LAZILY so a
  // raw/read-only pipe pays nothing; wView writes fixed-width ints straight into it.
  let wbuf: Uint8Array | null = null,
    wlen = 0,
    wView: DataView | null = null;
  function ensureWbuf(): void {
    if (wbuf === null) {
      wbuf = new Uint8Array(8192);
      wView = new DataView(wbuf.buffer);
    }
  }
  // One outstanding write/flush/close at a time, ENFORCED at the public boundary (mirrors the
  // read side's concurrent-receive throw): every sink op passes its result through writeLock(), which
  // claims the pipe while an async op is in flight; a second op then throws instead of silently
  // interleaving mutations of the staging segment. Internal continuations call the closure
  // functions directly and are not re-guarded.
  let wbusy = false;
  function checkWriteBusy(): void {
    if (wbusy) throw new Error("concurrent write on the same pipe");
  }
  function writeLock(r: MaybePromise): MaybePromise {
    if (!r) return;
    wbusy = true;
    return r.then(
      function () {
        wbusy = false;
      },
      function (e) {
        wbusy = false;
        throw e;
      },
    );
  }

  // No more data either way — drop routing.
  function maybeReap(): void {
    if (weEnded && (writeEnded || readClosed)) delete pipes[id];
  }

  // ---- core byte channel ----

  // undefined = fully accepted; a Promise = backpressure, resolved on DRAIN (buffer must stay
  // unmodified until then); -1 from the host (read closed) -> EPIPE.
  function send(buffer: Uint8Array, offset?: number, len?: number): MaybePromise {
    const o = offset || 0,
      l = len === undefined ? buffer.length - o : len;
    if (l === 0) return;
    if (weEnded) return Promise.reject(new Error("send() after close()")); // our own half-close is a misuse
    if (peerReadClosed) return Promise.reject(brokenPipe(id, command));
    if (writer) throw new Error("concurrent send() on the same pipe"); // sequential: await one send first
    const written = nHostData(id, buffer, o, l);
    if (written < 0) {
      peerReadClosed = true;
      return Promise.reject(brokenPipe(id, command));
    }
    if (written >= l) return;
    return new Promise<void>(function (res, rej) {
      writer = { buffer: buffer, pos: o + written, end: o + l, resolve: res, reject: rej };
    });
  }

  // Abandon the read side: release the buffer and make the host's next send EPIPE.
  function closeRead(): void {
    if (readClosed) return;
    readClosed = true;
    nPbCloseRead(pb);
    nHostCloseRead(id);
    maybeReap();
  }

  // Write-side half-close (END if no reason, else RESET). The public close() flushes staged writes first.
  function closeWrite(reason?: string): void {
    if (weEnded) return;
    weEnded = true;
    nHostClose(id, reason === undefined ? null : reason);
    maybeReap();
  }

  // ---- buffered-write internals (into wbuf; undefined = sync, Promise = backpressure — leave the
  // staged segment unmodified until it resolves) ----

  // wlen stays claimed until a parked send completes: a non-awaiting caller then appends beyond
  // the in-flight window (and fails on the concurrent-send guard) instead of corrupting it.
  function flushSink(): MaybePromise {
    if (wlen === 0) return;
    const p = send(wbuf!, 0, wlen);
    if (!p) {
      wlen = 0;
      return;
    }
    return p.then(function () {
      wlen = 0;
    });
  }

  function putByte(b: number): MaybePromise {
    if (wlen + 1 > wbuf!.length) {
      const p = flushSink();
      if (p)
        return p.then(function () {
          wbuf![wlen++] = b;
        });
    }
    wbuf![wlen++] = b;
  }

  // >= a segment bypasses staging: stream to the pipe in segment-sized slices, paced by the reader.
  function writeBytesFn(b: Uint8Array): MaybePromise {
    const n = b.length;
    if (n >= wbuf!.length) {
      let off = 0;
      const pump = function (): MaybePromise {
        while (off < n) {
          const m = Math.min(wbuf!.length, n - off);
          const q = send(b, off, m);
          off += m;
          if (q) return q.then(pump);
        }
      };
      const pf = flushSink();
      return pf ? pf.then(pump) : pump();
    }
    if (wlen + n > wbuf!.length) {
      const p = flushSink();
      if (p)
        return p.then(function () {
          wbuf!.set(b, wlen);
          wlen += n;
        });
    }
    wbuf!.set(b, wlen);
    wlen += n;
  }

  // (to - from) * 3 bounds the encoded size (the UTF-16 worst case), deciding fit up front:
  // a string that fits coalesces into the staging segment (__utf8Into, no allocation); one too
  // big for a whole segment is encoded once in C (__utf8Encode, exact-size zero-copy buffer)
  // and streamed by send() under the normal backpressure.
  function writeUtf8Native(str: string, from: number, to: number): MaybePromise {
    const max = (to - from) * 3;
    if (max > wbuf!.length - wlen) {
      // no room in the remainder: flush (sync flush leaves wlen 0)
      const p = flushSink();
      if (p)
        return p.then(function () {
          return writeUtf8Native(str, from, to);
        });
    }
    if (max > wbuf!.length) return send(nUtf8Encode(str, from, to)); // too big even for an empty segment
    wlen += nUtf8Into(str, from, to, wbuf!, wlen);
  }

  // stream-encode the parts back-to-back (no `a + b + c` concat first)
  function writeUtf8Parts(args: string[]): MaybePromise {
    if (args.length === 1) {
      const s = args[0]!;
      return writeUtf8Native(s, 0, s.length);
    }
    let k = 0;
    const next = function (): MaybePromise {
      while (k < args.length) {
        const str = args[k++]!;
        const p = writeUtf8Native(str, 0, str.length);
        if (p) return p.then(next);
      }
    };
    return next();
  }

  return {
    id: id,
    command: command,

    // ---- the readable end (C-owned buffer; value when already buffered, else a Promise C
    // settles). close() abandons it: the host's next send gets EPIPE. ----
    source: {
      receive: function (buffer, offset, len) {
        const o = offset || 0;
        return nPbRecv(pb, buffer, o, len === undefined ? buffer.length - o : len);
      },
      readInt: function () {
        return nPbInt(pb);
      },
      readLong: function () {
        return nPbLong(pb);
      }, // number: loses precision above 2^53
      // strict requires a '\n' (throws at EOF); else the final unterminated line, then null at EOF
      readUtf8Line: function (strict) {
        return nPbLine(pb, !!strict);
      },
      // stops at the value's end, leaving trailing bytes queued; root type checked on the first byte
      readJsonObject: function () {
        return nPbJson(pb, 1) as MaybePromise<Record<string, unknown>>;
      },
      readJsonArray: function () {
        return nPbJson(pb, 2) as MaybePromise<unknown[]>;
      },
      readUtf8: function () {
        return nPbUtf8(pb);
      },
      readBytes: function () {
        return nPbBytes(pb);
      },
      close: closeRead,
    },

    // ---- the writable end (buffered writes: undefined = done synchronously, a Promise =
    // backpressure). close() = END; close(reason) = RESET. ----
    sink: {
      send: function (buffer, offset, len) {
        checkWriteBusy();
        return writeLock(send(buffer, offset, len));
      },
      writeBytes: function (b) {
        checkWriteBusy();
        ensureWbuf();
        return writeLock(writeBytesFn(b));
      },
      putByte: function (b) {
        checkWriteBusy();
        if (!Number.isInteger(b) || b < 0 || b > 255)
          throw new RangeError("putByte: " + b + " is not a byte");
        ensureWbuf();
        return writeLock(putByte(b));
      },
      // out-of-range throws rather than silently wrapping (DataView truncates mod 2^n)
      writeInt: function (v) {
        checkWriteBusy();
        if (!Number.isInteger(v) || v < -2147483648 || v > 2147483647)
          throw new RangeError("writeInt: " + v + " is not a signed 32-bit integer");
        ensureWbuf();
        if (wlen + 4 > wbuf!.length) {
          const p = flushSink();
          if (p)
            return writeLock(
              p.then(function () {
                wView!.setInt32(wlen, v);
                wlen += 4;
              }),
            );
        }
        wView!.setInt32(wlen, v);
        wlen += 4;
      },
      writeLong: function (v) {
        checkWriteBusy();
        const b = typeof v === "bigint" ? v : BigInt(Math.trunc(v));
        if (b < -0x8000000000000000n || b > 0x7fffffffffffffffn)
          throw new RangeError("writeLong: " + b + " does not fit in a signed 64-bit long");
        ensureWbuf();
        if (wlen + 8 > wbuf!.length) {
          const p = flushSink();
          if (p)
            return writeLock(
              p.then(function () {
                wView!.setBigInt64(wlen, b);
                wlen += 8;
              }),
            );
        }
        wView!.setBigInt64(wlen, b);
        wlen += 8;
      },
      writeUtf8: function (...parts) {
        checkWriteBusy();
        ensureWbuf();
        return writeLock(writeUtf8Parts(parts));
      },
      writeUtf8Range: function (str, from, to) {
        checkWriteBusy();
        ensureWbuf();
        return writeLock(writeUtf8Native(str, from, to));
      },
      writeUtf8Line: function (...parts) {
        checkWriteBusy();
        ensureWbuf();
        const p = writeUtf8Parts(parts);
        return writeLock(
          p
            ? p.then(function () {
                return putByte(10);
              })
            : putByte(10),
        );
      },
      writeBytesAsBase64url: function (b) {
        checkWriteBusy();
        ensureWbuf();
        let off = 0;
        const n = b.length;
        const pump = function (): MaybePromise {
          while (off < n) {
            const rem = n - off;
            const need = rem >= 3 ? 4 : rem === 2 ? 3 : 2;
            if (wlen + need > wbuf!.length) {
              const p = flushSink();
              if (p) return p.then(pump);
            }
            const wrote = nB64urlInto(b, off, rem, wbuf!, wlen);
            if (wrote <= 0) break;
            wlen += wrote;
            // A non-multiple of 4 means the native also encoded the 1–2 byte tail: all consumed.
            if (wrote % 4 === 0) off += 3 * (wrote / 4);
            else off = n;
          }
        };
        return writeLock(pump());
      },
      // matches JSON.stringify (a cycle throws TypeError); streams via the native encoder,
      // never materializing the whole JSON string
      writeObjectAsJson: function (value) {
        checkWriteBusy();
        ensureWbuf();
        const enc = nJsonEncNew(value);
        const pump = function (): MaybePromise {
          for (;;) {
            wlen += nJsonEncFill(enc, wbuf!, wlen);
            if (nJsonEncDone(enc)) return;
            const p = flushSink();
            if (p) return p.then(pump);
          }
        };
        return writeLock(pump());
      },
      flush: function () {
        checkWriteBusy();
        return writeLock(flushSink());
      },
      // ABORT — an explicit RESET, or an END that must interrupt a parked send (a clean END then
      // would truncate the peer's stream, so it becomes a RESET instead). Discards staged bytes,
      // fails the parked send, RESETs the peer; bypasses the busy-guard. A plain END with nothing
      // in flight takes the clean path below.
      close: function (reason) {
        if (reason !== undefined || writer) {
          const resetReason = reason !== undefined ? reason : "closed while a send was in progress";
          wlen = 0;
          const w = writer;
          if (w) {
            writer = null;
            w.reject(new Error("send aborted: " + resetReason));
          }
          closeWrite(resetReason);
          return;
        }
        checkWriteBusy();
        // The half-close MUST run even if the final flush fails (EPIPE: the peer closed its read
        // end, or a host error) — otherwise weEnded stays false, __hostClose is never sent, and
        // the pipe plus its wbuf leak for the runtime's life while the peer never learns we
        // ended. Half-close on success AND failure, then propagate the flush error to the caller.
        let p: MaybePromise;
        try {
          p = flushSink();
        } catch (e) {
          closeWrite(reason);
          throw e;
        }
        if (p)
          return writeLock(
            p.then(
              function () {
                closeWrite(reason);
              },
              function (e) {
                closeWrite(reason);
                throw e;
              },
            ),
          );
        closeWrite(reason);
      },
    },

    // ---- transport entry points (host -> us; the data path never comes through JS) ----

    _end: function () {
      writeEnded = true;
      maybeReap();
    },

    _abandon: function () {
      readClosed = true;
      nPbCloseRead(pb);
    },

    _peerCloseRead: function () {
      peerReadClosed = true;
      const w = writer;
      if (w) {
        writer = null;
        w.reject(brokenPipe(id, command));
      }
    },

    _drain: function () {
      const w = writer;
      if (!w) return;
      const written = nHostData(id, w.buffer, w.pos, w.end - w.pos);
      if (written < 0) {
        writer = null;
        peerReadClosed = true;
        w.reject(brokenPipe(id, command));
        return;
      }
      w.pos += written;
      if (w.pos >= w.end) {
        writer = null;
        w.resolve();
      }
      // else stay parked for the next drain
    },
  };
}

// Kotlin opened a pipe to us. Returns 0 (accepted) or -1 (refused: no handler) — a refusal
// fails Kotlin's openPipe() itself; no pipe is created on either side.
globalThis.__jsOpen = function (id, command) {
  const handler = accepts[command];
  if (!handler) return -1;
  const pipe = makePipe(id, command);
  pipes[id] = pipe;
  Promise.resolve()
    .then(function () {
      return handler(pipe);
    })
    .catch(function (e: unknown) {
      pipe.source.close();
      pipe.sink.close(String((e as Error | null)?.message || e));
    }); // RESET: surface the failure
  return 0;
};
// Kotlin closed its write end (END or RESET — the C buffer already knows which).
globalThis.__jsClose = function (id) {
  const p = pipes[id];
  if (p) p._end();
};
// Kotlin abandoned its read end — our sends get EPIPE.
globalThis.__jsCloseRead = function (id) {
  const p = pipes[id];
  if (p) p._peerCloseRead();
};
// Kotlin flow-control signal: its buffer drained (continue the parked partial send).
globalThis.__jsDrain = function (id) {
  const p = pipes[id];
  if (p) p._drain();
};

// Open a pipe to the host. Fails AT THE OPEN — like ECONNREFUSED — when nothing serves the
// command: __hostOpen returns a C-style code (0 accepted; -1 refused / host callback failed;
// -2 called under a critical region, a transport bug), and a failed open leaves no registry
// entry behind on either side.
export function open(command: string): Pipe {
  const id = nextId;
  nextId += 2;
  const pipe = makePipe(id, command);
  pipes[id] = pipe; // registered first so anything crossing during the call finds the pipe
  const rc = nHostOpen(id, command);
  if (rc < 0) {
    delete pipes[id];
    pipe._abandon();
    throw new Error(
      rc === -2
        ? 'open("' + command + '") during data delivery'
        : 'open("' + command + '") refused by host',
    );
  }
  return pipe;
}

// Replaces any previous handler for `command`. Returns a registration whose remove()
// unregisters it — a no-op once a newer registration has replaced this one.
export function onAccept(
  command: string,
  handler: (pipe: Pipe) => void | Promise<void>,
): { remove(): void } {
  accepts[command] = handler;
  return {
    remove: function () {
      if (accepts[command] === handler) delete accepts[command];
    },
  };
}

const eventListeners: Record<string, Array<(n: string) => void>> = {};

export function onEvent(name: string, handler: (name: string) => void): { remove(): void } {
  let list = eventListeners[name];
  if (!list) {
    list = [];
    eventListeners[name] = list;
  }
  list.push(handler);
  return {
    remove: function () {
      const cur = eventListeners[name];
      if (!cur) return;
      const i = cur.indexOf(handler);
      if (i >= 0) cur.splice(i, 1);
      if (cur.length === 0) delete eventListeners[name];
    },
  };
}

onAccept("event", function (pipe) {
  const p = pipe.source.readUtf8();
  const run = function (name: string) {
    pipe.source.close();
    const c = pipe.sink.close();
    const list = eventListeners[name];
    if (list) {
      for (let i = 0; i < list.length; i++) list[i]!(name);
    }
    return c;
  };
  if (typeof p === "string") return run(p);
  return p.then(run);
});

// Native decode (JS_NewStringLen) — astral-correct, no per-codepoint JS loop. Range args avoid
// a subarray view: utf8Decode(bytes, from, to).
export function utf8Decode(bytes: Uint8Array, from?: number, to?: number): string {
  const f = from === undefined ? 0 : from;
  return nUtf8Decode(bytes, f, (to === undefined ? bytes.length : to) - f);
}

export { utf8Encode, stringifyJson };

// The global surface mirrors the module exports — in-bundle code imports these instead. Quoted
// keys keep the names for eval'd JS after Closure ADVANCED.
globalThis["ymix"] = {
  "open": open,
  "onAccept": onAccept,
  "onEvent": onEvent,
  "utf8Encode": utf8Encode,
  "utf8Decode": utf8Decode,
};

// ---- Host-scheduled timers: setTimeout/setInterval over the pipe protocol (see Timers.kt). A
// cleared timer's async loop ends when the host closes the pipe (receive -> EOF). A callback that
// throws rejects this async task and, for setInterval, stops the interval.
//
// clear*() marks the timer cleared BEFORE closing the pipe, so a tick the host had already committed
// when the clear arrived (a benign host-side select race) cannot still fire the callback — matching
// browser/Node semantics where a cleared timer never runs. Callers see a numeric id. The pipe stays
// in the slot map.

interface TimerSlot {
  cb: ((...args: unknown[]) => void) | null;
  cleared: boolean;
  pipe: Pipe;
}
const timers = new Map<number, TimerSlot>();
let nextTimerId = 1;

function startTimer(
  cb: (...args: unknown[]) => void,
  ms: number | undefined,
  command: string,
  repeat: boolean,
  args: unknown[],
): number {
  const pipe = open(command);
  // Delay as a big-endian 8-byte long — fixed width self-frames it (Kotlin reads it with okio
  // readLong).
  pipe.sink.writeLong(Math.max(0, Math.floor(Number(ms) || 0)));
  pipe.sink.flush();
  const id = nextTimerId++;
  if (nextTimerId > 0x7fffffff) nextTimerId = 1;
  const slot: TimerSlot = { cb: cb, cleared: false, pipe: pipe };
  timers.set(id, slot);
  const buf = new Uint8Array(1);
  (async function () {
    try {
      for (;;) {
        const n = await pipe.source.receive(buf, 0, 1);
        if (n < 1 || slot.cleared) break; // EOF, or cleared while this tick was in flight
        slot.cb!.apply(null, args);
        if (!repeat) break;
      }
    } catch (e) {
      if (!slot.cleared) throw e; // a live callback/pipe error surfaces; a cleared pipe's does not
    } finally {
      if (timers.get(id) === slot) timers.delete(id);
      pipe.sink.close();
      pipe.source.close();
    }
  })();
  return id;
}
export function setTimeout(
  cb: (...args: unknown[]) => void,
  ms?: number,
  ...args: unknown[]
): number {
  return startTimer(cb, ms, "setTimeout", false, args);
}
export function setInterval(
  cb: (...args: unknown[]) => void,
  ms?: number,
  ...args: unknown[]
): number {
  return startTimer(cb, ms, "setInterval", true, args);
}
export function clearTimeout(handle: number | undefined): void {
  if (handle == null || handle === 0) return;
  const slot = timers.get(handle);
  if (!slot) return;
  slot.cleared = true; // gate the loop BEFORE signaling: a committed tick can no longer fire
  slot.cb = null; // drop the callback reference
  timers.delete(handle);
  try {
    slot.pipe.sink.close();
  } catch (_e) {
    /* already closing/closed — clearing is idempotent */
  }
}
export const clearInterval = clearTimeout;

function asNative<T extends Function>(name: string, impl: T): T {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl) as unknown as T;
  return impl;
}
globalThis.setTimeout = asNative("setTimeout", setTimeout);
globalThis.setInterval = asNative("setInterval", setInterval);
globalThis.clearTimeout = asNative("clearTimeout", clearTimeout);
globalThis.clearInterval = asNative("clearInterval", clearInterval);
