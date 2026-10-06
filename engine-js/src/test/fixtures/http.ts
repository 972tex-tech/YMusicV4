// Fixtures for DynamicEngineTest: each scenario is the typed twin of an old inline snippet —
// runs against the real HttpRequest/pipe surface and returns a result string ("ERR:" + message on
// a throw), so the Kotlin assertions stay identical. Registered as `test/http/<name>`; args cross
// as the JSON head. The raw-wire scenarios drive open("http") below HttpRequest, exactly like the
// snippets did — with QUOTED wire keys, since this file compiles through Closure like production.
import { open, onAccept, utf8Decode } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { installPlayerAdStrip } from "../../logic/player/skip_ads";

type Scenario = (args: Record<string, unknown>) => Promise<string>;

function gcPressure(): Promise<void> {
  const junk: Uint8Array[] = [];
  for (let i = 0; i < 64; i++) junk.push(new Uint8Array(256 * 1024));
  junk.length = 0;
  return new Promise<void>(function (resolve) {
    setTimeout(function () {
      resolve();
    }, 0);
  });
}

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

  "close-response": async () => {
    const res = await HttpRequest.get("https://x/ping").build().execute();
    res.close();
    return String(res.status);
  },

  "timeout-unused-body": async () => {
    const res = await HttpRequest.get("https://x/big").timeout(50).build().execute();
    await new Promise<void>(function (resolve) {
      setTimeout(function () {
        resolve();
      }, 200);
    });
    return String(res.status);
  },

  "gc-drop-response": async () => {
    let res: HttpResponse | null = await HttpRequest.get("https://x/big").build().execute();
    const status = res.status;
    const wr = new WeakRef(res);
    res = null;
    await gcPressure();
    return status + "|" + (wr.deref() == null ? "collected" : "held");
  },

  "fetch-gc-drop": async () => {
    let res: FetchResponse | null = await fetch("https://x/big");
    const status = res["status"];
    const wr = new WeakRef(res);
    res = null;
    await gcPressure();
    return status + "|" + (wr.deref() == null ? "collected" : "held");
  },

  "fire-and-forget": async () => {
    HttpRequest.get("https://x/big").timeout(50).build().execute();
    await new Promise<void>(function (resolve) {
      setTimeout(function () {
        resolve();
      }, 200);
    });
    return "started";
  },

  "fetch-fire-and-forget": async () => {
    fetch("https://x/big");
    await gcPressure();
    await new Promise<void>(function (resolve) {
      setTimeout(function () {
        resolve();
      }, 200);
    });
    return "started";
  },

  "xhr-timeout": async () => {
    const x = new XMLHttpRequest();
    x["timeout"] = 50;
    return await new Promise<string>(function (resolve) {
      x["ontimeout"] = function () {
        resolve("timeout");
      };
      x["onerror"] = function () {
        resolve("error");
      };
      x["onload"] = function () {
        resolve("load");
      };
      x.open("GET", "https://x/slow");
      x.send();
    });
  },

  "status-text": async () => {
    const res = await HttpRequest.get("https://example.com/missing").build().execute();
    return res.status + " " + res.ok + " " + (await res.text());
  },

  "fetch-get-text": async () => {
    const res = await fetch("https://example.com");
    return res["ok"] && res["status"] === 200 ? await res["text"]() : "FAIL";
  },

  "fetch-json": async () => {
    const res = await fetch("https://example.com/api");
    const body = (await res["json"]()) as Record<string, unknown>;
    return String((body["items"] as unknown[]).length);
  },

  "fetch-arraybuffer": async () => {
    const res = await fetch("https://example.com");
    const ab = await res["arrayBuffer"]();
    return utf8Decode(new Uint8Array(ab));
  },

  "fetch-reader": async () => {
    const res = await fetch("https://example.com/stream");
    const reader = res["body"]["getReader"]();
    const parts: Uint8Array[] = [];
    let firstDone: boolean | undefined;
    for (;;) {
      const r = await reader["read"]();
      if (firstDone === undefined) firstDone = r["done"] as boolean;
      if (r["done"]) break;
      parts.push(r["value"] as Uint8Array);
    }
    let total = 0;
    for (let i = 0; i < parts.length; i++) total += parts[i]!.length;
    const out = new Uint8Array(total);
    let off = 0;
    for (let i = 0; i < parts.length; i++) {
      const p = parts[i]!;
      out.set(p, off);
      off += p.length;
    }
    return utf8Decode(out) + "|" + String(firstDone) + "|true";
  },

  "fetch-clone": async () => {
    const res = await fetch("https://example.com");
    const copy = res["clone"]();
    const a = await copy["text"]();
    const b = await res["text"]();
    return a + "|" + b;
  },

  "fetch-post": async () => {
    await fetch("https://example.com/post", {
      "method": "POST",
      "headers": { "X-Test": "1", "Accept": "text/html" },
      "body": "payload",
    });
    return "sent";
  },

  "fetch-headers": async () => {
    const res = await fetch("https://example.com");
    return String(res["headers"]["get"]("content-type"));
  },

  "fetch-native": async () => {
    const src = Function.prototype.toString.call(fetch);
    const ac = new AbortController();
    return (
      String(typeof fetch) +
      "|" +
      String(src.indexOf("[native code]") !== -1) +
      "|" +
      String(typeof ReadableStream) +
      "|" +
      String(typeof AbortController) +
      "|" +
      String(ac["signal"]["aborted"] === false)
    );
  },

  "fetch-abort": async () => {
    const ac = new AbortController();
    ac["abort"]();
    try {
      await fetch("https://example.com", { "signal": ac["signal"] });
      return "resolved";
    } catch (err) {
      return (err as Error).name;
    }
  },

  "fetch-failed": async () => {
    try {
      await fetch("https://example.com");
      return "resolved";
    } catch (err) {
      return (err as Error).name + " " + (err as Error).message;
    }
  },

  "player-fetch-strips-ads": async () => {
    installPlayerAdStrip();
    const res = await fetch("https://example.com/youtubei/v1/player", {
      "headers": { "Authorization": "x" },
    });
    const copy = res["clone"]();
    const viaJson = (await copy["json"]()) as Record<string, unknown>;
    const viaText = JSON.parse((await res["text"]()).replace(")]}'", "")) as Record<
      string,
      unknown
    >;
    const vd = viaText["videoDetails"] as Record<string, unknown>;
    return (
      String(viaJson["adPlacements"] == null) +
      "|" +
      String(viaText["adPlacements"] == null) +
      "|" +
      String(vd["videoId"])
    );
  },

  "xhr-get-text": async () => {
    const x = new XMLHttpRequest();
    return await new Promise<string>(function (resolve, reject) {
      x["onreadystatechange"] = function () {
        if (x.readyState === 4) resolve(x.responseText);
      };
      x["onerror"] = function () {
        reject(new Error("xhr error"));
      };
      x.open("GET", "https://example.com");
      x.send();
    });
  },

  "xhr-post": async () => {
    const x = new XMLHttpRequest();
    await new Promise<void>(function (resolve, reject) {
      x["onload"] = function () {
        resolve();
      };
      x["onerror"] = function () {
        reject(new Error("xhr error"));
      };
      x.open("POST", "https://example.com/post");
      x.setRequestHeader("X-Test", "1");
      x.setRequestHeader("Accept", "text/html");
      x.send("payload");
    });
    return "sent";
  },

  "xhr-native": async () => {
    const src = Function.prototype.toString.call(XMLHttpRequest);
    const x = new XMLHttpRequest();
    return (
      String(typeof XMLHttpRequest) +
      "|" +
      String(src.indexOf("[native code]") !== -1) +
      "|" +
      String(x.readyState) +
      "|" +
      String(XMLHttpRequest.UNSENT)
    );
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
