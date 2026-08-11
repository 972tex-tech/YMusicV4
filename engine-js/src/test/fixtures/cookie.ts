// Fixtures for CookieProtocolTest: drive the Cookies client against the host jar (the Kotlin test's
// InMemoryCookieJar) purely via pipes. Registered as test/cookie/<name>; args cross as the JSON
// head. Compiled through Closure like production, so wire reads are bracketed.
import { onAccept } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { Cookies } from "../../protocol/cookie";

type Scenario = (args: Record<string, unknown>) => Promise<string>;

// Sorted "k=v; k=v" so the Kotlin assertion is order-stable regardless of map iteration order.
function render(m: Record<string, string>): string {
  return Object.keys(m)
    .sort()
    .map((k) => k + "=" + m[k])
    .join("; ");
}

const scenarios: Record<string, Scenario> = {
  "get": async (args) => render(await Cookies.get(String(args["url"]))),

  "set": async (args) => {
    await Cookies.set(String(args["url"]), args["setCookie"] as string | string[]);
    return "ok";
  },

  "set-then-get": async (args) => {
    await Cookies.set(String(args["url"]), args["setCookie"] as string | string[]);
    return render(await Cookies.get(String(args["url"])));
  },

  // Surfaces the host's classified RESET as code|message (the error contract).
  "get-err": async (args) => {
    try {
      await Cookies.get(String(args["url"]));
      return "no-throw";
    } catch (err) {
      const e = err as Error & { code?: string };
      return e.code + "|" + e.message;
    }
  },
};

for (const name of Object.keys(scenarios)) {
  onAccept(
    "test/cookie/" + name,
    commandHandler(async (src, out) => {
      const args = await src.readJsonObject();
      let r: string;
      try {
        r = await scenarios[name]!(args);
      } catch (e) {
        r = "ERR:" + String((e as Error | null)?.message || e);
      }
      const p = out.writeUtf8(r);
      if (p) await p;
    }),
  );
}
