// Fixture for HostLoggingTest: runs an HttpRequest with an optional logging-context trail so the
// Kotlin side can assert the birth-site log (trail + native cause) captured by its Logger. The
// request failure is deliberately swallowed — the test observes the LOG, not the error.
import { onAccept } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";

onAccept(
  "test/http-log",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const b = HttpRequest.get(String(args["url"]));
    const ctx = args["ctx"];
    if (ctx !== undefined) b.logContext(ctx as string[]);
    try {
      await (await b.build().execute()).text();
    } catch (e) {
      // swallowed: the assertion target is the host-side birth-site log
    }
    const p = out.writeUtf8("done");
    if (p) await p;
  }),
);
