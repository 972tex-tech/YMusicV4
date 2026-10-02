// On SPS≠1 the harness remills this program and POSTs the saved SABR URL.
// It does not call /player and does not reproduce the snapshot bytes.
// Required in the blob: program, interpJs (or interpUrl), eventId, mintId,
// pot, sabrUrl, sabrBody. sps is only a log line today, not a blob field.
// Cookie on that POST is not stored. snapshot, integrityToken, entropy,
// timeOrigin, and visitorData are kept but the SABR replay does not use them.
// Payloads go through cache/put, never console. SPS≠1 → log/capture attaches the blob.

import { debug } from "../../debug";
import { callHost } from "../../protocol/command";
import { keepCount, scopedCache } from "../../protocol/cache";
import { DESKTOP_UA } from "../yt/session";

const SCOPE = "yt.bg.session";
const KEEP = 4;
const held: Record<string, unknown> = {};
let sessionKey = "";
let persistChain: Promise<void> = Promise.resolve();
let dumpedSps = 0;
let dumpedFirstSps = 0;
let reportedReject = false;
let storeP: Promise<Awaited<ReturnType<typeof scopedCache>>> | null = null;

function u8ToB64url(u: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function store(): Promise<Awaited<ReturnType<typeof scopedCache>>> {
  if (!storeP) storeP = scopedCache(SCOPE, keepCount(KEEP));
  return storeP;
}

/** UTC `yyyyMMdd-HHmmss-SSS` — sorts as latest last, matches playground dump dirs. */
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

function persistHeld(): void {
  if (!sessionKey) sessionKey = utcStamp();
  const key = sessionKey;
  const snap: Record<string, unknown> = {};
  const names = [
    "eventId",
    "visitorData",
    "clientVersion",
    "globalName",
    "interpUrl",
    "interpJs",
    "program",
    "snapshot",
    "integrityToken",
    "programLen",
    "jsLen",
    "snapLen",
    "itLen",
    "ua",
    "mintId",
    "pot",
    "sabrUrl",
    "sabrBody",
    "entropy",
    "timeOrigin",
  ];
  for (let i = 0; i < names.length; i++) {
    const k = names[i]!;
    if (held[k] !== undefined) snap[k] = held[k];
  }
  snap["sessionKey"] = key;
  persistChain = persistChain.then(function () {
    return store()
      .then(function (s) {
        return s.setJson(key, snap);
      })
      .then(
        function () {},
        function (e: unknown) {
          console.warn(
            "bg session cache write skipped: " + (e instanceof Error ? e.message : String(e)),
          );
        },
      );
  });
}

function reportPoReject(sps: number): void {
  if (reportedReject) return;
  reportedReject = true;
  const key = sessionKey;
  persistChain = persistChain.then(function () {
    return callHost("log/capture", async function (pipe) {
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
      function (e: unknown) {
        console.warn(
          "bg po-reject report skipped: " + (e instanceof Error ? e.message : String(e)),
        );
      },
    );
  });
}

export function recordBgChallenge(p: Record<string, unknown>): void {
  const keys = [
    "eventId",
    "visitorData",
    "clientVersion",
    "globalName",
    "interpUrl",
    "interpJs",
    "program",
    "snapshot",
    "integrityToken",
    "programLen",
    "jsLen",
    "snapLen",
    "itLen",
  ];
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]!;
    if (p[k] !== undefined) held[k] = p[k];
  }
  held["ua"] = DESKTOP_UA;
  dumpedSps = 0;
  dumpedFirstSps = 0;
  reportedReject = false;
  sessionKey = utcStamp();
  persistHeld();
}

/** Snapshot-window Date.now / performance.now / Math.random — cache/put, not log. */
export function recordBgEntropy(): void {
  if (typeof __bgEntropyDump === "function") {
    const ent = __bgEntropyDump();
    if (typeof ent === "string" && ent.length) held["entropy"] = ent;
  }
  const perf = (globalThis as unknown as { performance?: { timeOrigin?: number } }).performance;
  if (perf && typeof perf.timeOrigin === "number") held["timeOrigin"] = perf.timeOrigin;
  persistHeld();
}

export function recordBgMint(id: Uint8Array, token: unknown): void {
  held["mintId"] = new TextDecoder().decode(id);
  if (token instanceof Uint8Array) held["pot"] = u8ToB64url(token);
  else if (typeof token === "string") held["pot"] = token;
  else held["pot"] = "";
  dumpedSps = 0;
  persistHeld();
}

/** Last /videoplayback POST — replay this body with a new PO to read SPS. */
export function recordSabrForDump(url: string, body: Uint8Array | null): void {
  held["sabrUrl"] = url;
  held["sabrBody"] = body && body.length ? u8ToB64url(body) : "";
  persistHeld();
}

/** Lengths + sessionKey only. Payloads live in the cache blob. */
export function dumpBgParams(why: string): void {
  debug(function () {
    const rec: Record<string, unknown> = {
      "why": why,
      "eventId": held["eventId"] || "",
      "clientVersion": held["clientVersion"] || "",
      "globalName": held["globalName"] || "",
      "interpUrl": held["interpUrl"] || "",
      "ua": held["ua"] || "",
      "requestKey": "O43z0dpjhgX20SCx4KAo",
      "programLen": held["programLen"] || 0,
      "jsLen": held["jsLen"] || 0,
      "snapLen": held["snapLen"] || 0,
      "itLen": held["itLen"] || 0,
      "mintId": held["mintId"] || "",
      "potLen": String(held["pot"] || "").length,
      "sabrUrlLen": String(held["sabrUrl"] || "").length,
      "entropyLen": String(held["entropy"] || "").length,
      "sessionKey": sessionKey || utcStamp(),
      "scope": SCOPE,
    };
    if (why.indexOf("sps=") === 0) rec["sps"] = Number(why.slice(4));
    if (held["timeOrigin"] !== undefined) rec["timeOrigin"] = held["timeOrigin"];
    else {
      const perf = (globalThis as unknown as { performance?: { timeOrigin?: number } }).performance;
      if (perf && typeof perf.timeOrigin === "number") rec["timeOrigin"] = perf.timeOrigin;
    }
    return "bg dump meta " + JSON.stringify(rec);
  });
}

export function dumpFirstSps(sps: number): void {
  if (sps <= 0) return;
  if (dumpedFirstSps !== 0) return;
  dumpedFirstSps = sps;
  debug(function () {
    return "bg dump sps " + sps;
  });
}

/** First SPS≠1 for this mint — persist cache blob, then Sentry attach. */
export function dumpBgOnSps(sps: number): void {
  dumpFirstSps(sps);
  if (sps === 1 || sps <= 0) return;
  recordBgEntropy();
  reportPoReject(sps);
  if (dumpedSps === sps) return;
  dumpedSps = sps;
  dumpBgParams("sps=" + sps);
}
