// Fixtures for the :quickjs transport tests (PipeIoTest, PipeCancellationTest, RuntimeCloseTest,
// HostDataBoundsTest): the JS halves of the old inline snippets, driven purely via pipes. Commands
// the fixtures OPEN toward the host ("report", "test-gate", "slow", "sink-hold") are served by the
// Kotlin tests' onAccept. Each test creates a fresh runtime, so module state (heldPipe) is
// per-test.
import { open, onAccept, utf8Encode } from "../../transport";
import { commandHandler } from "../../protocol/command";

function report(text: string): void {
  const out = open("report");
  out.sink.send(utf8Encode(text));
  out.sink.close();
}

// ---- PipeIoTest ----

// CRLF/LF lines fragmented so line breaks land across receive boundaries.
onAccept("test/lines", (pipe) => {
  const bytes = utf8Encode("alpha\r\nbeta🎵\ngamma\r\n");
  let i = 0;
  const step = (): void => {
    if (i >= bytes.length) {
      pipe.sink.close();
      return;
    }
    const n = Math.min(3, bytes.length - i); // 3-byte fragments split the \r\n pairs
    const p = pipe.sink.send(bytes, i, n);
    i += n;
    if (p) p.then(step);
    else step();
  };
  step();
});

// 200 KB deterministic pattern — many segments, many receive calls.
onAccept("test/blob", (pipe) => {
  const chunk = new Uint8Array(8192);
  let sent = 0;
  const total = 200 * 1024;
  const step = (): void => {
    if (sent >= total) {
      pipe.sink.close();
      return;
    }
    const n = Math.min(chunk.length, total - sent);
    for (let i = 0; i < n; i++) chunk[i] = (sent + i) & 0xff;
    const p = pipe.sink.send(chunk, 0, n);
    sent += n;
    if (p) p.then(step);
    else step();
  };
  step();
});

// Consumer checksums slowly (chunked receive) — a big host write must park and resume.
onAccept("test/sum", async (pipe) => {
  const buf = new Uint8Array(4096);
  let sum = 0,
    count = 0;
  for (;;) {
    const r = pipe.source.receive(buf, 0, buf.length);
    const n = typeof r === "number" ? r : await r;
    if (n < 0) break;
    for (let i = 0; i < n; i++) sum = (sum + buf[i]!) >>> 0;
    count += n;
  }
  pipe.sink.send(utf8Encode(count + ":" + sum));
  pipe.sink.close();
});

onAccept("test/boom", (pipe) => {
  pipe.sink.send(utf8Encode("partial"));
  pipe.sink.close("engine exploded");
});

onAccept("test/deaf", (pipe) => {
  pipe.source.close();
});

// ---- RuntimeCloseTest / PipeCancellationTest: held pipes + pokes ----

let heldPipe: Pipe | null = null;

// Keep the pipe without reading — the host side owns its fate.
onAccept("test/keep", (pipe) => {
  heldPipe = pipe;
});

// END immediately: our END + the peer's END reaps the pipe.
onAccept("test/close-now", async (pipe) => {
  const p = pipe.sink.close();
  if (p) await p;
});

// Try to send on the held pipe; the outcome ("accepted" / the error message) is the result.
onAccept(
  "test/poke-held",
  commandHandler(async (src, out) => {
    await src.readUtf8(); // consume the (empty) head to EOF
    let outcome: string;
    try {
      const p = heldPipe!.sink.send(utf8Encode("anyone?"));
      if (p) await p;
      outcome = "accepted";
    } catch (e) {
      outcome = String((e as Error).message);
    }
    const w = out.writeUtf8(outcome);
    if (w) await w;
  }),
);

// ---- PipeCancellationTest ----

// Hold reads until the host opens+ENDs the gate, then drain and report total|err.
onAccept("test/gated-drain", async (pipe) => {
  const gate = open("test-gate");
  await gate.source.receive(new Uint8Array(1), 0, 1); // parks until the host pokes
  gate.sink.close();
  gate.source.close();
  const buf = new Uint8Array(8192);
  let total = 0;
  let err = "none";
  try {
    for (;;) {
      const r = pipe.source.receive(buf, 0, buf.length);
      const n = typeof r === "number" ? r : await r;
      if (n < 0) break;
      total += n;
    }
  } catch (e) {
    err = String((e as Error).message);
  }
  report(total + "|" + err);
});

// Read once, then abandon the read side — lands inside the host's write pump.
onAccept("test/read-once-close", async (pipe) => {
  const buf = new Uint8Array(4096);
  const r = pipe.source.receive(buf, 0, buf.length);
  if (typeof r !== "number") await r;
  pipe.source.close(); // resolves in the pump of the host's first big write
});

// Receive once and report the outcome (read:n / error:msg) on the report pipe.
onAccept("test/recv-report", async (pipe) => {
  try {
    const buf = new Uint8Array(64);
    const r = pipe.source.receive(buf, 0, buf.length);
    const n = typeof r === "number" ? r : await r;
    report("read:" + n);
  } catch (e) {
    report("error:" + String((e as Error).message));
  }
});

// Open the host-served "slow" command and report how its read ends.
onAccept(
  "test/probe-slow",
  commandHandler(async (src, out) => {
    await src.readUtf8();
    const pipe = open("slow");
    let outcome: string;
    try {
      const buf = new Uint8Array(64);
      const r = pipe.source.receive(buf, 0, buf.length);
      const n = typeof r === "number" ? r : await r;
      outcome = "read:" + n;
    } catch (e) {
      outcome = "error:" + String((e as Error).message);
    }
    const w = out.writeUtf8(outcome);
    if (w) await w;
  }),
);

// ---- HostDataBoundsTest: __hostData range checks ----

// args {"off","len"}: send buf[off, off+len) into the host-held "sink-hold" pipe; the outcome
// ("accepted" / "throw:"+name) is the result.
onAccept(
  "test/send-bounds",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const pipe = open("sink-hold");
    const buf = new Uint8Array(16);
    let outcome: string;
    try {
      const r = pipe.sink.send(buf, Number(args["off"]), Number(args["len"]));
      if (r) await r;
      outcome = "accepted";
    } catch (e) {
      outcome = "throw:" + String((e as Error).name);
    }
    const w = out.writeUtf8(outcome);
    if (w) await w;
  }),
);
