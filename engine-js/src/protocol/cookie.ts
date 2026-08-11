// Cookies — the JS client for host-backed cookie storage, over the :quickjs pipe protocol. The host
// (Kotlin CookieProtocol.kt) is the jar: it owns persistence and the domain/path/expiry rules;
// YouTube cookie handling lives in this remotely-updatable scraping JS but the storage does not.
// One one-shot pipe per call (like http — HOST_PROTOCOLS §0): the pipe IS the correlation handle, so
// concurrent calls just open concurrent pipes, no request-ids, no session framing. Host-classified
// failures RESET the pipe and are decoded by callHost/hostError (../command) into a coded error.
// Bundled into the :engine-js single bundle (src/index.ts); never shipped as source.

import { callHost } from "./command";

// All cookies that apply to `url`, as a name->value map. Wire keys QUOTED — the head crosses to
// Kotlin as JSON and must survive Closure's ADVANCED renaming literally; the reply is host wire
// (bracket/Record access), not a declared structure.
function get(url: string): Promise<Record<string, string>> {
  return callHost("cookie/get", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({ "url": url });
    if (p) await p;
    p = pipe.sink.flush();
    if (p) await p;
    return (await pipe.source.readJsonObject()) as Record<string, string>;
  });
}

// Store the `Set-Cookie` header value(s) received for `url` — a single header or the whole array a
// response carries. No response body: the host applies then ENDs (empty) on success, or RESETs on
// failure — readUtf8 to EOF surfaces either (callHost decodes it).
function set(url: string, setCookie: string | ReadonlyArray<string>): Promise<void> {
  const list = typeof setCookie === "string" ? [setCookie] : setCookie;
  return callHost("cookie/set", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({ "url": url, "setCookie": list });
    if (p) await p;
    p = pipe.sink.flush();
    if (p) await p;
    p = pipe.sink.close(); // END our write
    if (p) await p;
    await pipe.source.readUtf8();
  });
}

// In-bundle code imports this; no global mirror and no ambient declaration (nothing externally
// evaled needs it — the scraping logic and fixtures reach it through this ES import).
export const Cookies = { get: get, set: set };
