// Per-session remill file over cache/put. Each record is prefix + JSON + newline, fire-and-forget.
// keepCount(4) trims old session keys after close. Payloads are not kept in JS.

import { callHost } from "../../protocol/command";
import { keepCount, scopedCache } from "../../protocol/cache";
import { open } from "../../transport";
import { DESKTOP_UA } from "../yt/session";

const SCOPE = "yt.bg.session";
const KEEP = 4;
let dump: Pipe | null = null;
let writeChain: Promise<void> = Promise.resolve();
let sessionKey = "";
let loggedSps = 0;
let dumpFinished = false;
let lastSabrUrl = "";
let lastSabrBody = "";
let dumpWriteFn: ((name: string, text: string) => void) | null = null;

export function setBgDumpExtras(extras: Record<string, unknown> | null): void {
  dumpWriteFn = null;
  if (!extras) return;
  const w = extras["dumpWrite"];
  if (typeof w === "function") dumpWriteFn = w as (name: string, text: string) => void;
}

function u8ToB64url(u: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function utcStamp(): string {
  const iso = new Date().toISOString();
  return (
    iso.slice(0, 4) +
    iso.slice(5, 7) +
    iso.slice(8, 10) +
    "-" +
    iso.slice(11, 13) +
    iso.slice(14, 16) +
    iso.slice(17, 19) +
    "-" +
    iso.slice(20, 23)
  );
}

function wait(p: unknown): Promise<void> | void {
  if (p && typeof (p as Promise<void>).then === "function") return p as Promise<void>;
}

function warn(e: unknown, what: string): void {
  console.warn("bg dump " + what + " skipped: " + (e instanceof Error ? e.message : String(e)));
}

function ensureDump(): Pipe | null {
  if (dump) return dump;
  sessionKey = utcStamp();
  try {
    dump = open("cache/put");
  } catch (e) {
    warn(e, "open");
    return null;
  }
  const pipe = dump;
  writeChain = Promise.resolve(
    wait(
      pipe.sink.writeObjectAsJson({
        "scope": SCOPE,
        "key": sessionKey,
        "meta": {},
      }),
    ),
  ).then(
    function () {},
    function (e) {
      warn(e, "head");
      dump = null;
    },
  );
  return dump;
}

function writeJsonLine(prefix: string, value: unknown): void {
  const pipe = ensureDump();
  if (!pipe) return;
  writeChain = writeChain
    .then(function () {
      return wait(pipe.sink.writeUtf8(prefix, " "));
    })
    .then(function () {
      return wait(pipe.sink.writeObjectAsJson(value));
    })
    .then(function () {
      return wait(pipe.sink.writeUtf8("\n"));
    })
    .then(
      function () {},
      function (e) {
        warn(e, "write");
        dump = null;
      },
    );
}

function finishDump(after?: () => void): void {
  const pipe = dump;
  dump = null;
  if (!pipe) {
    if (after) after();
    return;
  }
  writeChain = writeChain
    .then(function () {
      return wait(pipe.sink.close());
    })
    .then(function () {
      return pipe.source.readJsonObject();
    })
    .then(function () {
      pipe.source.close();
      return scopedCache(SCOPE, keepCount(KEEP));
    })
    .then(
      function () {
        if (after) after();
      },
      function (e) {
        try {
          pipe.source.close();
        } catch (_e) {}
        warn(e, "close");
        if (after) after();
      },
    );
}

function captureReject(sps: number): void {
  const key = sessionKey;
  callHost("log/capture", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({
      "code": "E20088",
      "message": "PO rejected sps=" + sps,
      "scope": SCOPE,
      "key": key,
    });
    if (p) await p;
    p = pipe.sink.close();
    if (p) await p;
    await pipe.source.readUtf8();
  }).then(
    function () {},
    function (e) {
      console.warn("bg po-reject report skipped: " + (e instanceof Error ? e.message : String(e)));
    },
  );
}

function dumpFile(name: string, text: string): void {
  if (dumpWriteFn) dumpWriteFn(name, text);
}

function timeOriginMs(): number {
  const g = globalThis as unknown as Record<string, unknown>;
  const p = g["performance"] as Record<string, unknown> | undefined;
  const n = p && p["timeOrigin"];
  return typeof n === "number" ? n : 0;
}

export function recordBotguardEntropy(): void {
  if (typeof __bgEntropyDump !== "function") return;
  const ent = __bgEntropyDump();
  if (typeof ent !== "string" || !ent.length) return;
  writeJsonLine("entropy", ent);
  const tape = ent.charAt(ent.length - 1) === "\n" ? ent : ent + "\n";
  dumpFile("entropy.txt", "# timeOrigin " + timeOriginMs() + "\n" + tape);
}

export function recordBotguardInit(p: Record<string, unknown>): void {
  loggedSps = 0;
  dumpFinished = false;
  dump = null;
  writeChain = Promise.resolve();
  lastSabrUrl = "";
  lastSabrBody = "";
  p["ua"] = DESKTOP_UA;
  p["timeOrigin"] = timeOriginMs();
  writeJsonLine("init", p);
  if (typeof p["program"] === "string") dumpFile("program.txt", p["program"] as string);
  if (typeof p["interpJs"] === "string") dumpFile("interpreter.js", p["interpJs"] as string);
  dumpFile(
    "meta.json",
    JSON.stringify({
      "eventId": p["eventId"] || "",
      "interpUrl": p["botguardJsUrl"] || "",
      "globalName": p["globalName"] || "trayride",
      "visitorData": p["visitorData"] || "",
      "clientVersion": p["clientVersion"] || "",
      "ua": DESKTOP_UA,
      "timeOrigin": p["timeOrigin"],
      "sessionKey": sessionKey,
    }) + "\n",
  );
}

export function recordBotguardSnapshot(s: string): void {
  writeJsonLine("snapshot", s);
  dumpFile("snapshot.txt", s);
}

export function recordBotguardSnapshotResponse(it: string): void {
  writeJsonLine("snapshotResponse", it);
  dumpFile("integrityToken.txt", it);
}

export function recordBotguardMint(id: Uint8Array, token: unknown): void {
  const rec: Record<string, unknown> = { "id": new TextDecoder().decode(id) };
  if (token instanceof Uint8Array) rec["pot"] = u8ToB64url(token);
  else if (typeof token === "string") rec["pot"] = token;
  writeJsonLine("mint", rec);
}

export function recordSabrForDump(url: string, body: Uint8Array | null): void {
  lastSabrUrl = url;
  lastSabrBody = body && body.length ? u8ToB64url(body) : "";
}

export function noteSps(sps: number): void {
  if (!(sps > 0)) return;
  if (loggedSps !== sps) {
    loggedSps = sps;
    console.info("bg sps=" + sps);
  }
  if (dumpFinished) return;
  dumpFinished = true;
  if (sps !== 1) {
    writeJsonLine("sabr", { "url": lastSabrUrl, "body": lastSabrBody });
  }
  lastSabrUrl = "";
  lastSabrBody = "";
  if (typeof __bgEntropyStop === "function") __bgEntropyStop();
  if (sps === 1) finishDump();
  else
    finishDump(function () {
      captureReject(sps);
    });
}
