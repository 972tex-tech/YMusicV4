// Fixtures for the pipe_stress ASan harness (quickjs/native/pipe_stress.c): the handlers its C
// scenarios drive via qjs_open/qjs_write. Results land on plain globalThis properties ("r1"…) the
// C reads back with qjs_eval_sync — runtime properties are untouched by mangling, so that seam
// survives ADVANCED. Command names are the harness's contract (t1…t7, park/jsonpark/full).
import { open, onAccept } from "../../transport";

const g = globalThis as unknown as Record<string, unknown>;

// S1: every read type over worst-case fragmentation (one byte per write).
onAccept("t1", async (p) => {
  const line = await p.source.readUtf8Line(true);
  const obj = await p.source.readJsonObject();
  const i = await p.source.readInt();
  const l = await p.source.readLong();
  const rest = await p.source.readUtf8();
  const eof = await p.source.receive(new Uint8Array(1), 0, 1);
  g["r1"] = line + "|" + JSON.stringify(obj) + "|" + i + "|" + l + "|" + rest + "|" + eof;
});

// S2: fill the 64 KB cap against a gated reader (C pokes globalThis.go2), then drain and finish.
onAccept("t2", async (p) => {
  await new Promise(function (res) {
    g["go2"] = res;
  });
  let total = 0,
    sum = 0;
  const buf = new Uint8Array(4096);
  for (;;) {
    const n = await p.source.receive(buf, 0, buf.length);
    if (n < 0) break;
    total += n;
    for (let i = 0; i < n; i++) sum = (sum + buf[i]!) & 0xffff;
  }
  g["r2"] = total + ":" + sum;
});

// S3: RESET lands in a parked strict line read as a thrown reason.
onAccept("t3", (p) => {
  return Promise.resolve(p.source.readUtf8Line(true)).then(
    function (v) {
      g["r3"] = "ok:" + v;
    },
    function (err: Error) {
      g["r3"] = "err:" + err.message;
    },
  );
});

// S4: JS closeRead -> host write returns EPIPE; a read after closeRead throws.
onAccept("t4", (p) => {
  p.source.close();
  try {
    p.source.readInt();
    g["r4"] = "no-throw";
  } catch (err) {
    g["r4"] = "threw:" + (err as Error).message;
  }
});

// S5 (opened by C as a trigger): JS writes through the staging sink to the C-served slow host,
// paced by drains; r5 = "sent" when the whole body flushed and closed.
onAccept("t5", () => {
  const p = open("slow");
  g["p5"] = p;
  const big = "x".repeat(50000) + "🎵";
  Promise.resolve(p.sink.writeUtf8(big))
    .then(function () {
      return p.sink.flush();
    })
    .then(function () {
      return p.sink.close();
    })
    .then(function () {
      g["r5"] = "sent";
    });
});

// S6 (opened by C as a trigger): a refused open leaves nothing behind on either side.
onAccept("t6", () => {
  try {
    open("nope");
    g["r6"] = "opened";
  } catch (err) {
    g["r6"] = (err as Error).message;
  }
});

// S7: GC with a parked JSON job (gc_mark must keep dst/capability/parser alive), then complete it.
onAccept("t7", async (p) => {
  const obj = await p.source.readJsonObject();
  g["r7"] = JSON.stringify(obj);
});

// Teardown scenario: parked reads (incl. a JSON job holding half-built containers), a never-drained
// buffer, and a live registry (finalizer + gc_mark paths at qjs_close).
onAccept("park", (p) => {
  return Promise.resolve(p.source.readUtf8()).then(function () {});
});
onAccept("jsonpark", (p) => {
  return Promise.resolve(p.source.readJsonObject()).then(function () {});
});
onAccept("full", () => {
  return new Promise(function () {});
});
