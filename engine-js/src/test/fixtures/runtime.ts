// Fixtures for QuickJsRuntimeTest — verbatim ports of the old inline snippets. Two shapes:
// direct handlers Kotlin drives raw (test/rt/echo, …), and "run"-style probes that open
// Kotlin-served commands with their ORIGINAL names ("feed", "sink", "upper", …) and report their
// result string back on their own pipe. Pure-native snippets (the __json*/__utf8* one-shots)
// stayed in the Kotlin file as load() snippets — natives are externed and always present.
import {
  open,
  onAccept,
  utf8Encode,
  utf8Decode,
  stringifyJson,
  setTimeout,
  setInterval,
  clearTimeout,
  clearInterval,
} from "../../transport";

// Report a probe's result on its own pipe and END.
function runProbe(command: string, body: (pipe: Pipe) => Promise<string>): void {
  onAccept(command, async (pipe) => {
    const msg = await body(pipe);
    pipe.sink.send(utf8Encode(msg));
    pipe.sink.close();
  });
}

// ---- direct handlers (Kotlin drives them raw) ----

// Echo: stream bytes straight back, then close.
onAccept("test/rt/echo", async (pipe) => {
  const buf = new Uint8Array(64);
  for (;;) {
    const n = await pipe.source.receive(buf, 0, buf.length);
    if (n < 0) break;
    pipe.sink.send(buf, 0, n);
  }
  pipe.sink.close();
});

// "parse": Kotlin sends [kind line 'o'|'a'][json...]; parse with the matching typed reader and
// return either JSON.stringify(value) or "ERR:" + the thrown message.
onAccept("test/rt/parse", async (pipe) => {
  const kind = await pipe.source.readUtf8Line();
  try {
    const data =
      kind === "o" ? await pipe.source.readJsonObject() : await pipe.source.readJsonArray();
    pipe.sink.send(stringifyJson(data));
  } catch (e) {
    pipe.sink.send(utf8Encode("ERR:" + (e as Error).message));
  }
  pipe.sink.close();
});

onAccept("test/rt/stream", async (pipe) => {
  pipe.sink.send(Uint8Array.of(1, 2, 3));
  pipe.sink.send(Uint8Array.of(4, 5));
  pipe.sink.send(Uint8Array.of(6));
  pipe.sink.close();
});

// Reader-side cancel: close our read end immediately — the host's next send EPIPEs.
onAccept("test/rt/deaf", async (pipe) => {
  pipe.source.close();
});

// One byte received before a Kotlin RESET, then the reset reason observed on the next receive.
onAccept("test/rt/reset-down", async (pipe) => {
  const buf = new Uint8Array(16);
  const n = await pipe.source.receive(buf, 0, buf.length);
  let err = "none";
  try {
    await pipe.source.receive(buf, 0, buf.length);
  } catch (e) {
    err = String((e as Error).message ?? e);
  }
  pipe.sink.send(utf8Encode(n + "|" + err)); // our write end is still open
  pipe.sink.close();
});

onAccept("test/rt/reset-up", async (pipe) => {
  pipe.sink.close("kaboom mid-stream");
});

onAccept("test/rt/reset-sym", async (pipe) => {
  pipe.sink.close(Symbol("boom") as unknown as string); // uncoercible reason (audit S1)
});

// Send parks at the cap, then END while parked -> the peer must see a RESET (no truncation).
onAccept("test/rt/push", (pipe) => {
  const sendP = pipe.sink.send(new Uint8Array(200000)); // parks: 64 KB in, rest waits
  if (sendP && sendP.catch) sendP.catch(function () {}); // it will be force-failed
  pipe.sink.close(); // END while parked -> abort the send, RESET the peer
});

// Keep the pipe and never read — the host's send fills the cap and parks.
let kept: Pipe | null = null;
onAccept("test/rt/blackhole", async (pipe) => {
  kept = pipe;
});
void kept;

onAccept("test/rt/jsboom", async (): Promise<void> => {
  throw new Error("js blew up");
});

// Fixed-width int echo through the buffered source/sink.
onAccept("test/rt/intecho", async (pipe) => {
  const i = await pipe.source.readInt();
  const l = await pipe.source.readLong();
  pipe.sink.writeInt(i);
  pipe.sink.writeLong(l);
  const f = pipe.sink.flush();
  if (f) await f;
  pipe.sink.close();
});

onAccept("test/rt/longin", async (pipe) => {
  const v = await pipe.source.readLong();
  pipe.sink.writeLong(v);
  const f = pipe.sink.flush();
  if (f) await f;
  pipe.sink.close();
});

onAccept("test/rt/bigcheck", async (pipe) => {
  let msg = "no throw";
  try {
    pipe.sink.writeLong(0x8000000000000000n);
  } catch (e) {
    msg = "threw: " + (e as Error).constructor.name;
  }
  pipe.sink.writeUtf8(msg);
  const f = pipe.sink.flush();
  if (f) await f;
  pipe.sink.close();
});

// END-close whose final flush EPIPEs must still half-close; the outcome reports via a side pipe.
onAccept("test/rt/staged-end", async (pipe) => {
  pipe.sink.writeUtf8("staged"); // into wbuf, unflushed
  const go = new Uint8Array(1);
  await pipe.source.receive(go, 0, 1); // wait until Kotlin has closed its read
  let result: string;
  try {
    const c = pipe.sink.close(); // END: flush -> EPIPE -> must still half-close
    if (c) await c;
    result = "resolved";
  } catch (e) {
    result = /^EPIPE/.test((e as Error).message) ? "epipe" : "other:" + (e as Error).message;
  }
  const r = open("staged-end-result");
  const w = r.sink.writeUtf8(result);
  if (w) await w;
  const c2 = r.sink.close();
  if (c2) await c2;
});

// Handshake byte, then hold the pipe open forever (runtime close must fail the parked receive).
onAccept("test/rt/silent-hs", async (pipe) => {
  pipe.sink.send(Uint8Array.of(1));
});

// Backpressured Kotlin -> JS drain with checksum report.
onAccept("test/rt/down", async (pipe) => {
  const buf = new Uint8Array(4096);
  let len = 0,
    sum = 0;
  for (;;) {
    const n = await pipe.source.receive(buf, 0, buf.length);
    if (n < 0) break;
    len += n;
    for (let i = 0; i < n; i++) sum += buf[i]!;
  }
  pipe.sink.send(utf8Encode(len + ":" + sum));
  pipe.sink.close();
});

// ---- probes (open Kotlin-served commands by their original names, report on own pipe) ----

runProbe("test/rt/buffered-read-json", async () => {
  const io = open("feed");
  const data = await io.source.readJsonObject();
  return JSON.stringify(data);
});

runProbe("test/rt/read-json-tail", async () => {
  const io = open("feed");
  const data = await io.source.readJsonObject(); // stops right after '}'
  const tail = await io.source.readUtf8(); // everything after the JSON value
  return JSON.stringify(data) + "|" + tail;
});

runProbe("test/rt/nested-upper", async (pipe) => {
  const input = await pipe.source.readUtf8();
  const inner = open("upper");
  let p = inner.sink.writeUtf8(input);
  if (p) await p;
  p = inner.sink.close();
  if (p) await p;
  return await inner.source.readUtf8();
});

runProbe("test/rt/sink-2000-lines", async () => {
  const io = open("sink");
  // 2000 lines "ABCDE\n" = 12000 bytes > the 8 KB segment, so the sink auto-flushes at
  // least once mid-write; the reassembled stream must still be identical.
  for (let i = 0; i < 2000; i++) {
    const p = io.sink.writeUtf8Line("ABCDE");
    if (p) await p;
  }
  const c = io.sink.close();
  if (c) await c;
  return await io.source.readUtf8();
});

runProbe("test/rt/sink-large-writes", async () => {
  const io = open("sink");
  // A multibyte+astral string repeated so codepoints land across many segment boundaries,
  // then a >segment byte array that bypasses the buffer and is streamed in slices.
  const s = "héllo🎵\n".repeat(30000); // stream-encoded into the segment
  const b = new Uint8Array(400000); // >= segment -> bypass, sliced to the pipe
  for (let i = 0; i < b.length; i++) b[i] = i & 0xff;
  const sbytes = utf8Encode(s);
  const len = sbytes.length + b.length;
  let sum = 0;
  for (let i = 0; i < sbytes.length; i++) sum += sbytes[i]!;
  for (let i = 0; i < b.length; i++) sum += b[i]!;
  let p = io.sink.writeUtf8(s);
  if (p) await p;
  p = io.sink.writeBytes(b);
  if (p) await p;
  p = io.sink.close();
  if (p) await p;
  const got = await io.source.readUtf8();
  const want = len + ":" + sum;
  return got === want ? "OK" : "MISMATCH want=" + want + " got=" + got;
});

// Direct-path writeUtf8 (too big to stage) keeps strict order around staged neighbors.
onAccept("test/rt/direct-order", async (pipe) => {
  const big = "x🎵".repeat(30000); // 90000 units -> 150000 bytes: direct + backpressure
  let p;
  p = pipe.sink.writeUtf8("head|");
  if (p) await p;
  p = pipe.sink.writeUtf8(big);
  if (p) await p;
  p = pipe.sink.writeUtf8("|tail");
  if (p) await p;
  const c = pipe.sink.close();
  if (c) await c;
});

runProbe("test/rt/json-stringify-match", async () => {
  const big: number[] = [];
  for (let i = 0; i < 5000; i++) big.push(i); // pushes output past a segment
  const value = {
    s: 'q"uote back\\slash nl\n cr\r tab\t bell slash/ emoji🎵 unicodé',
    n: 42,
    f: 3.5,
    neg: -7,
    exp: 1e21,
    nan: NaN,
    inf: Infinity,
    neginf: -Infinity,
    t: true,
    f2: false,
    nil: null,
    arr: [1, "two", null, [3, 4], { x: 5 }, undefined, function () {}, Symbol("s")], // -> null
    obj: { a: 1, skip: undefined, fn: function () {}, sym: Symbol("k"), c: 3 }, // omitted
    empty: {},
    emptyArr: [],
    date: new Date(0), // toJSON honored
    big: big,
  };
  const io = open("sink");
  const w = io.sink.writeObjectAsJson(value);
  if (w) await w;
  const c = io.sink.close();
  if (c) await c;
  const got = await io.source.readUtf8();
  const want = JSON.stringify(value);
  if (got === want) return "OK";
  let k = 0;
  while (k < got.length && k < want.length && got[k] === want[k]) k++;
  return (
    "MISMATCH@" +
    k +
    " want[..." +
    want.slice(Math.max(0, k - 15), k + 15) +
    "...] got[..." +
    got.slice(Math.max(0, k - 15), k + 15) +
    "...]"
  );
});

runProbe("test/rt/timer-fires", async () => {
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
  let fired = false;
  setTimeout(() => {
    fired = true;
  }, 30);
  await sleep(90); // outlast the 30ms timer
  return fired ? "FIRED" : "NOT";
});

runProbe("test/rt/timer-cleared", async () => {
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
  let fired = false;
  const t = setTimeout(() => {
    fired = true;
  }, 30);
  clearTimeout(t);
  await sleep(90);
  return fired ? "FIRED" : "CLEARED";
});

runProbe("test/rt/interval-ticks", async () => {
  const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));
  let n = 0;
  const t = setInterval(() => {
    n++;
  }, 20);
  // Wait for ticks, not for a fixed time: a loaded CI runner may fire only one in 110 ms.
  for (let waited = 0; n < 3 && waited < 5000; waited += 20) await sleep(20);
  clearInterval(t);
  const after = n;
  await sleep(70); // a cleared timer never fires again — not even an in-flight tick
  const ok = after >= 3 && n === after;
  return ok ? "OK" : "BAD:" + after + "/" + n;
});

runProbe("test/rt/concurrent-receive", async () => {
  const io = open("silent");
  const buf = new Uint8Array(8);
  io.source.receive(buf, 0, 8); // first: pends (no data arrives)
  let err = "no-error";
  try {
    await io.source.receive(buf, 0, 8);
  } catch (e) {
    err = String((e as Error).message);
  }
  return err;
});

// strict flag arrives as one byte '1'/'0'; probes the Kotlin-served "feed".
runProbe("test/rt/line-strict", async (pipe) => {
  const flag = new Uint8Array(1);
  const strict = (await pipe.source.receive(flag, 0, 1)) === 1 && flag[0] === 49; // '1' -> strict
  const io = open("feed");
  const l1 = await io.source.readUtf8Line(strict); // "a" — terminated by '\n'
  let l2: string;
  try {
    const r = await io.source.readUtf8Line(strict);
    l2 = r === null ? "NULL" : r;
  } catch (e) {
    l2 = "THREW";
  }
  return l1 + "|" + l2;
});

runProbe("test/rt/line-io", async () => {
  const io = open("echo");
  let p = io.sink.writeUtf8Line("hello🎵");
  if (p) await p;
  p = io.sink.writeUtf8Line("world");
  if (p) await p;
  p = io.sink.close(); // done sending -> echo returns the rest and ends
  if (p) await p;
  const l1 = await io.source.readUtf8Line();
  const l2 = await io.source.readUtf8Line();
  const l3 = await io.source.readUtf8Line(); // null at EOF
  return l1 + "|" + l2 + "|" + (l3 === null ? "EOF" : l3);
});

runProbe("test/rt/line-split-frames", async () => {
  const io = open("lines");
  const l1 = await io.source.readUtf8Line();
  const l2 = await io.source.readUtf8Line();
  const l3 = await io.source.readUtf8Line();
  return l1 + "|" + l2 + "|" + (l3 === null ? "EOF" : l3);
});

runProbe("test/rt/native-parse-stream", async () => {
  const body = open("fetch");
  body.sink.close();
  // the one-shot __parseJson wants the raw bytes whole — accumulate them here
  let buf = new Uint8Array(4096),
    size = 0;
  for (;;) {
    if (size === buf.length) {
      const g = new Uint8Array(buf.length * 2);
      g.set(buf);
      buf = g;
    }
    const n = await body.source.receive(buf, size, buf.length - size);
    if (n < 0) break;
    size += n;
  }
  // bracket access: parsed JSON is data, not declared structure (ADVANCED renames dotted)
  const data = __parseJson(buf.subarray(0, size)) as Record<string, unknown>;
  return String(data["title"]) + ":" + (data["items"] as unknown[]).length;
});

runProbe("test/rt/send-after-close", async () => {
  const io = open("idle");
  io.sink.close();
  let err = "none";
  try {
    const p = io.sink.send(utf8Encode("x"));
    if (p) await p;
  } catch (e) {
    err = String((e as Error).message ?? e);
  }
  return err;
});

runProbe("test/rt/receive-after-close-read", async () => {
  const io = open("idle");
  io.source.close();
  let err = "none";
  try {
    await io.source.receive(new Uint8Array(4), 0, 4);
  } catch (e) {
    err = String((e as Error).message ?? e);
  }
  return err;
});

runProbe("test/rt/epipe-loop", async () => {
  const io = open("up");
  let err = "none";
  try {
    for (let i = 0; i < 1000000; i++) {
      const p = io.sink.send(utf8Encode("x"));
      if (p) await p;
    }
  } catch (e) {
    err = String((e as Error).message ?? e);
  }
  return err;
});

runProbe("test/rt/up-big", async () => {
  const io = open("up-big");
  const chunk = new Uint8Array(4096);
  for (let i = 0; i < chunk.length; i++) chunk[i] = i & 0xff;
  let sum = 0;
  for (let i = 0; i < chunk.length; i++) sum += chunk[i]!;
  const rounds = 125; // 500 KB, ~8x the 64 KB buffer -> many partial writes + parks
  for (let r = 0; r < rounds; r++) {
    const p = io.sink.send(chunk, 0, chunk.length);
    if (p) await p;
  }
  io.sink.close();
  const got = await io.source.readUtf8();
  const want = rounds * chunk.length + ":" + rounds * sum;
  return got === want ? "OK" : "MISMATCH want=" + want + " got=" + got;
});

runProbe("test/rt/open-unknown", async () => {
  try {
    open("no-such-command");
    return "no-throw";
  } catch (e) {
    return String((e as Error).message ?? e);
  }
});

runProbe("test/rt/kotlin-boom", async () => {
  const io = open("boom");
  try {
    await io.source.receive(new Uint8Array(8), 0, 8);
    return "none";
  } catch (e) {
    return String((e as Error).message ?? e);
  }
});

runProbe("test/rt/sync-writes", async () => {
  const io = open("sink");
  const value = { a: [1, "two🎵"], b: null };
  const rs = [
    io.sink.writeUtf8("hello"),
    io.sink.writeUtf8Line("x", "y"),
    io.sink.writeObjectAsJson(value),
    io.sink.flush(),
  ];
  const allSync = rs.every((r) => r === undefined);
  const c = io.sink.close();
  if (c) await c;
  const got = await io.source.readUtf8();
  const want = "hello" + "xy\n" + JSON.stringify(value);
  return !allSync ? "NOT-SYNC" : got === want ? "OK" : "MISMATCH got=" + got;
});

runProbe("test/rt/json-depth", async () => {
  const depth = 5000; // deep enough to blow a recursive walker's stack
  let v: unknown = 1;
  for (let i = 0; i < depth; i++) v = [v];
  const io = open("sink");
  const w = io.sink.writeObjectAsJson(v);
  if (w) await w;
  const c = io.sink.close();
  if (c) await c;
  const got = await io.source.readUtf8();
  const want = "[".repeat(depth) + "1" + "]".repeat(depth);
  return got === want ? "OK" : "MISMATCH len=" + got.length + " want=" + want.length;
});

runProbe("test/rt/json-ropes", async () => {
  let rope = ""; // repeated concat -> a rope string
  for (let i = 0; i < 400; i++) rope += "seg" + i + "-héllo🎵|"; // wide + Latin-1 mix
  let latin = ""; // Latin-1-only rope (narrow storage)
  for (let i = 0; i < 300; i++) latin += "café-æß" + i; // all < U+0100 -> narrow storage
  const value = { rope: rope, latin: latin, esc: rope + '\n\t"' };
  const io = open("sink");
  const w = io.sink.writeObjectAsJson(value);
  if (w) await w;
  const c = io.sink.close();
  if (c) await c;
  const got = await io.source.readUtf8();
  const want = JSON.stringify(value);
  return got === want ? "OK" : "MISMATCH len=" + got.length + " want=" + want.length;
});

runProbe("test/rt/json-cycle", async () => {
  const a: Record<string, unknown> = { name: "a" };
  a["self"] = { deeper: [1, a] }; // cycle through two levels
  const io = open("sink");
  let msg = "no-throw";
  try {
    const w = io.sink.writeObjectAsJson(a);
    if (w) await w;
  } catch (e) {
    msg = e instanceof TypeError ? "TYPE_ERROR" : "WRONG:" + e;
  }
  io.sink.close("abort");
  return msg;
});

runProbe("test/rt/long-line-over-cap", async () => {
  const io = open("lines");
  const l1 = (await io.source.readUtf8Line())!;
  const l2 = await io.source.readUtf8Line();
  const l3 = (await io.source.readUtf8Line())!;
  const l4 = await io.source.readUtf8Line();
  let s1 = 0;
  for (let i = 0; i < l1.length; i++) s1 += l1.charCodeAt(i);
  let s3 = 0;
  for (let i = 0; i < l3.length; i++) s3 += l3.charCodeAt(i);
  return (
    l1.length + ":" + s1 + "|" + l2 + "|" + l3.length + ":" + s3 + "|" + (l4 === null ? "EOF" : l4)
  );
});

runProbe("test/rt/long-line-segments", async () => {
  const io = open("lines");
  const l1 = (await io.source.readUtf8Line())!;
  const l2 = await io.source.readUtf8Line();
  const l3 = await io.source.readUtf8Line();
  // report a checksum of l1 rather than echoing 20 KB back
  let sum = 0;
  for (let i = 0; i < l1.length; i++) sum += l1.charCodeAt(i);
  return l1.length + ":" + sum + "|" + l2 + "|" + (l3 === null ? "EOF" : l3);
});

runProbe("test/rt/utf8-range", async () => {
  const io = open("sink");
  const w = io.sink.writeUtf8Range("XXhello🎵YY", 2, 9); // "hello🎵" (surrogate pair = 2 units)
  if (w) await w;
  const c = io.sink.close();
  if (c) await c;
  return await io.source.readUtf8();
});

// Concurrent buffered write / abort-close probes: report via the "report" pipe (the parked write
// makes the own-pipe channel unusable).
onAccept("test/rt/concurrent-write", async () => {
  const out = open("report");
  const pipe = open("slowsink");
  const first = pipe.sink.writeBytes(new Uint8Array(200000)); // parks: the host isn't reading yet
  const kind = first !== undefined ? "parked" : "sync";
  let e1 = "none",
    e2 = "none";
  try {
    pipe.sink.writeInt(1);
  } catch (e) {
    e1 = String((e as Error).message);
  }
  try {
    pipe.sink.flush();
  } catch (e) {
    e2 = String((e as Error).message);
  }
  await first;
  pipe.sink.writeUtf8("after");
  const f = pipe.sink.flush();
  if (f) await f;
  const c = pipe.sink.close();
  if (c) await c;
  out.sink.send(utf8Encode(kind + "|" + e1 + "|" + e2));
  out.sink.close();
});

onAccept("test/rt/reset-parked-write", async () => {
  const out = open("report");
  const pipe = open("stuck");
  const first = pipe.sink.writeBytes(new Uint8Array(200000)); // parks: the host isn't reading
  let closeErr = "none";
  try {
    pipe.sink.close("deliberate abort");
  } catch (e) {
    closeErr = String((e as Error).message);
  }
  let firstErr = "none";
  try {
    await first;
  } catch (e) {
    firstErr = String((e as Error).message);
  }
  out.sink.send(utf8Encode(closeErr + "|" + firstErr));
  out.sink.close();
});

// Registration lifecycle, driven step by step: 1 = register "svc" (one), 2 = replace with (two) +
// stale remove, 3 = register-and-remove (open must then refuse).
let reg1: { remove(): void } | null = null;
runProbe("test/rt/reg-step", async (pipe) => {
  const step = new Uint8Array(1);
  await pipe.source.receive(step, 0, 1);
  if (step[0] === 49) {
    reg1 = onAccept("svc", async (p) => {
      p.sink.send(utf8Encode("one"));
      p.sink.close();
    });
  } else if (step[0] === 50) {
    onAccept("svc", async (p) => {
      p.sink.send(utf8Encode("two"));
      p.sink.close();
    });
    reg1!.remove(); // stale — replaced above; must not unregister the new handler
  } else {
    onAccept("svc", async (p) => {
      p.sink.close();
    }).remove();
  }
  return "done";
});

runProbe("test/rt/probe-svc", async () => {
  try {
    const io = open("svc");
    return await io.source.readUtf8();
  } catch (e) {
    return "ERR:" + String((e as Error).message);
  }
});

runProbe("test/rt/prefix-main", async () => {
  const io = open("host/main/echo-suffix");
  const w = io.sink.writeUtf8("arg");
  if (w) await w;
  await io.sink.close();
  return await io.source.readUtf8();
});
