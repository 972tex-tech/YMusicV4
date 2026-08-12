// Fixture for HostLoggingTest: runs an HttpRequest with an optional logging-context trail so the
// Kotlin side can assert the birth-site log (trail + native cause) captured by its Logger. The
// request failure is deliberately swallowed — the test observes the LOG, not the error.
import { onAccept } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { hostLogger } from "../../protocol/logger";

onAccept(
  "test/http-log",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const b = HttpRequest.get(String(args["url"]));
    const ctx = args["ctx"];
    if (ctx !== undefined) b.logContext(ctx as string[]);
    // The v2 path: the Kotlin caller's parked key + a JS-side fork with props.
    if (typeof args["logKey"] === "number")
      b.logger(hostLogger(args, "js:fetch").fork("sub", { "n": 1 }));
    try {
      await (await b.build().execute()).text();
    } catch (e) {
      // swallowed: the assertion target is the host-side birth-site log
    }
    const p = out.writeObjectAsJson({ "done": true });
    if (p) await p;
  }),
);
