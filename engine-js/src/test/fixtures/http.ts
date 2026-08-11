// Fixtures for DynamicEngineTest: each scenario is the typed twin of an old inline snippet —
// runs against the real HttpRequest/pipe surface and returns a result string ("ERR:" + message on
// a throw), so the Kotlin assertions stay identical. Registered as `test/http/<name>`; args cross
// as the JSON head. The raw-wire scenarios drive open("http") below HttpRequest, exactly like the
// snippets did — with QUOTED wire keys, since this file compiles through Closure like production.
import { open, onAccept, utf8Decode } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";

type Scenario = (args: Record<string, unknown>) => Promise<string>;

const scenarios: Record<string, Scenario> = {
  "versions": async () => "app=" + versions.app,

  "get-text": async () => {
    const res = await HttpRequest.get("https://example.com").build().execute();
    return res.ok && res.status === 200 ? await res.text() : "FAIL";
  },

  // Reused by several tests: whole body as text; a failure surfaces as ERR via the wrapper.
  "text": async (args) => {
    const res = await HttpRequest.get(String(args["url"])).build().execute();
    return await res.text();
  },

  "json-object-items": async () => {
    const res = await HttpRequest.get("https://example.com/api").build().execute();
    const body = await res.jsonObject();
    return String((body["items"] as unknown[]).length);
  },

  "json-array-len": async () => {
    const res = await HttpRequest.get("https://x/").build().execute();
    return String((await res.jsonArray()).length);
  },

  "json-tail-junk": async () => {
    try {
      await (await HttpRequest.get("https://x/").build().execute()).jsonObject();
      return "no-throw";
    } catch (err) {
      const e = err as Error;
      return e.name + " " + e.message;
    }
  },

  "json-trailing-ws": async () => {
    const b = await (await HttpRequest.get("https://x/").build().execute()).jsonObject();
    return String(b["id"]);
  },

  "header-get": async () => {
    const res = await HttpRequest.get("https://example.com").build().execute();
    return String(res.headers.get("content-type"));
  },

  "post-body": async () => {
    await HttpRequest.post("https://example.com/post")
      .setHeader("X-Test", "1")
      .setHeader("Accept", "text/html")
      .body("payload")
      .build()
      .execute();
    return "sent";
  },

  // Every UTF-8 width incl. a LONE surrogate (WTF-8, 3 bytes): Content-Length is measured by
  // __utf8Length and the bytes are produced by __utf8Into/__utf8Encode — they must agree.
  "post-body-unicode": async () => {
    await HttpRequest.post("https://example.com/post").body("aé€𝄞\uD800!").build().execute();
    return "sent";
  },

  "json-body": async () => {
    await HttpRequest.post("https://example.com/api")
      .json({ "q": "term", "n": 3 })
      .build()
      .execute();
    return "sent";
  },

  "body-callback": async () => {
    // The callback owns the body: streams two writes, no Content-Length (chunk-framed).
    await HttpRequest.post("https://example.com/upload")
      .body(async (io) => {
        const a = io.writeUtf8("chunk-");
        if (a) await a;
        const b = io.writeUtf8("data");
        if (b) await b;
      })
      .build()
      .execute();
    return "sent";
  },

  // Drive the wire raw: HttpRequest can't emit junk after the head.
  "raw-junk": async () => {
    const io = open("http");
    let p = io.sink.writeObjectAsJson({ "method": "GET", "url": "https://x", "headers": [] });
    if (p) await p;
    p = io.sink.writeInt(5);
    if (p) await p;
    p = io.sink.flush();
    if (p) await p;
    return String(await io.source.readJsonObject()); // the RESET reason lands on this read
  },

  "big-upload": async () => {
    const big = new Uint8Array(200000);
    let sum = 0;
    for (let i = 0; i < big.length; i++) {
      big[i] = i & 0xff;
      sum += big[i]!;
    }
    const res = await HttpRequest.post("https://x/upload").body(big).build().execute();
    return (await res.text()) + ":" + sum;
  },

  "bodiless-build-error": async () => {
    HttpRequest.post("https://x").build();
    return "built";
  },

  "empty-body-post": async () => {
    const res = await HttpRequest.post("https://x").body("").build().execute();
    return await res.text();
  },

  "opts-set": async () => {
    await HttpRequest.get("https://x").timeout(5000).followRedirects(false).build().execute();
    return "sent";
  },

  "opts-default": async () => {
    await HttpRequest.get("https://x").build().execute();
    return "sent";
  },

  "final-url": async () => {
    return (await HttpRequest.get("https://example.com/short").build().execute()).url;
  },

  "stream-total": async () => {
    const res = await HttpRequest.get("https://example.com/stream").build().execute();
    const s = res.stream();
    const buf = new Uint8Array(4);
    let total = 0;
    for (;;) {
      const n = await s.read(buf, 0, buf.length);
      if (n < 0) break;
      total += n;
    }
    return String(total);
  },

  "inject-value": async () => {
    HttpRequest.get("https://x").setHeader("X-A", "a\nInjected: 1");
    return "no-throw";
  },

  "inject-name": async () => {
    HttpRequest.get("https://x").setHeader("X:B", "v");
    return "no-throw";
  },

  "inject-url": async () => {
    HttpRequest.get("https://x/\n999 sneaky");
    return "no-throw";
  },

  "sanitized": async () => {
    await HttpRequest.get("https://x")
      .setHeaderSanitized("X-A\n", " a\nInjected: 1 ")
      .addHeaderSanitized("X:B", "v\u0000v")
      .build()
      .execute();
    return "sent";
  },

  "set-add-header": async () => {
    await HttpRequest.get("https://x")
      .setHeader("X-M", "old")
      .addHeader("X-M", "kept")
      .setHeader("x-m", "new")
      .addHeader("X-M", "extra")
      .build()
      .execute();
    return "sent";
  },

  "header-newline": async () => {
    const res = await HttpRequest.get("https://x").build().execute();
    return res.headers.get("X-Bad") + "|" + (await res.text());
  },

  "err-code": async () => {
    try {
      await HttpRequest.get("https://example.com").build().execute();
      return "resolved";
    } catch (err) {
      const e = err as Error & { code?: string };
      return e.code + "|" + e.message;
    }
  },

  "raw-malformed-head": async () => {
    const io = open("http");
    let p = io.sink.writeUtf8("[]");
    if (p) await p;
    p = io.sink.flush();
    if (p) await p;
    return String(await io.source.readJsonObject()); // the RESET reason lands on this read
  },

  "raw-torn-cancel": async () => {
    const io = open("http");
    let p = io.sink.writeObjectAsJson({ "method": "GET", "url": "https://x", "headers": [] });
    if (p) await p;
    p = io.sink.writeBytes(new Uint8Array([0, 0])); // half an int32
    if (p) await p;
    p = io.sink.flush();
    if (p) await p;
    p = io.sink.close(); // END mid-int
    if (p) await p;
    return String(await io.source.readJsonObject());
  },

  "cancel-after-execute": async () => {
    const res = await HttpRequest.get("https://x/big").build().execute();
    res.cancel();
    return "done";
  },

  "cancel-parked": async () => {
    const call = HttpRequest.get("https://x/slow").build();
    const done = call.execute().then(
      () => "resolved",
      (err: Error) => "ERR:" + err.message,
    );
    await open("entered").source.readUtf8(); // the server is now parked in execute
    call.cancel();
    return await done;
  },

  "cancel-parked-body": async () => {
    const call = HttpRequest.post("https://x/upload").body("never pulled").build();
    const done = call.execute().then(
      () => "resolved",
      (err: Error) => "ERR:" + err.message,
    );
    await open("entered").source.readUtf8(); // the server is parked in execute; body unpulled
    call.cancel();
    return await done;
  },

  "cancel-before": async () => {
    const call = HttpRequest.get("https://x").build();
    call.cancel();
    try {
      await call.execute();
      return "resolved";
    } catch (err) {
      return "ERR:" + (err as Error).message;
    }
  },

  "cancel-stall": async () => {
    const res = await HttpRequest.get("https://x/stall").build().execute();
    const s = res.stream();
    const buf = new Uint8Array(16);
    const n = await s.read(buf, 0, buf.length); // the one chunk that does arrive
    res.cancel();
    return utf8Decode(buf, 0, n < 0 ? 0 : n);
  },

  "ignore-result": async () => {
    const res = await HttpRequest.get("https://x/ping").build().execute();
    res.ignoreResult();
    return String(res.status);
  },

  "status-text": async () => {
    const res = await HttpRequest.get("https://example.com/missing").build().execute();
    return res.status + " " + res.ok + " " + (await res.text());
  },
};

for (const name of Object.keys(scenarios)) {
  onAccept(
    "test/http/" + name,
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
