// Built-in diagnostics commands — health checks the host can drive at any time, and the pipe-only
// smoke surface for the production bundle (no JS symbol needed: Kotlin opens these by command
// name). They are also what keeps the protocol layer reachable for the ADVANCED pass: they import
// commandHandler/HttpRequest, so dead-code elimination keeps exactly what production uses.
import { onAccept } from "../transport";
import { commandHandler } from "../protocol/command";
import { HttpRequest } from "../protocol/http";

// Echo: whatever bytes arrive (to EOF) come back verbatim. Proves the pipe transport + the
// commandHandler error contract end to end.
onAccept(
  "ping",
  commandHandler(async (src, out) => {
    const text = await src.readUtf8();
    const p = out.writeUtf8(text);
    if (p) await p;
  }),
);

// Fetch: args head {"url"} -> response head {"status"} + the raw body streamed to END. Proves the
// whole HTTP protocol path (builder -> call -> host client -> response streaming) over the wire
// contract; a transport failure RESETs with the classified {code:"network"} error.
onAccept(
  "http/fetch",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const res = await HttpRequest.get(String(args["url"])).build().execute();
    const ph = out.writeObjectAsJson({ "status": res.status });
    if (ph) await ph;
    const pf = out.flush(); // the raw send() below bypasses the buffered staging — ship the head first
    if (pf) await pf;
    const stream = res.stream();
    const buf = new Uint8Array(8192);
    for (;;) {
      const n = await stream.read(buf, 0, buf.length);
      if (n < 0) break;
      if (n > 0) {
        const p = out.send(buf, 0, n);
        if (p) await p;
      }
    }
  }),
);
