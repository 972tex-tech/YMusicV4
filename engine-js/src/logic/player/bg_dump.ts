// BotGuard remill dump: append-only log lines. Reproduce greps `bg dump` and glues
// `KIND i/n` chunks. No JSON blob, no in-memory session map.
// Last SABR POST is kept only until SPS≠1 so that reject can append it.

import { callHost } from "../../protocol/command";
import { DESKTOP_UA } from "../yt/session";

const DUMP_CHUNK = 3000;
let lastSabrUrl = "";
let lastSabrBody = "";
let loggedSps = 0;
let reportedReject = false;

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

function logField(kind: string, s: string): void {
  console.info("bg dump " + kind + " " + s);
}

function logBig(kind: string, s: string): void {
  if (!s.length) {
    console.info("bg dump " + kind + " 0/1 ");
    return;
  }
  const n = Math.ceil(s.length / DUMP_CHUNK);
  for (let i = 0; i < n; i++) {
    console.info(
      "bg dump " + kind + " " + i + "/" + n + " " + s.slice(i * DUMP_CHUNK, (i + 1) * DUMP_CHUNK),
    );
  }
}

function dumpEntropy(): void {
  if (typeof __bgEntropyDump === "function") {
    const ent = __bgEntropyDump();
    if (typeof ent === "string" && ent.length) logBig("entropy", ent);
  }
  const perf = (globalThis as unknown as { performance?: { timeOrigin?: number } }).performance;
  if (perf && typeof perf.timeOrigin === "number") logField("timeOrigin", String(perf.timeOrigin));
}

function reportPoReject(sps: number): void {
  if (reportedReject) return;
  reportedReject = true;
  dumpEntropy();
  if (lastSabrUrl) logBig("sabrUrl", lastSabrUrl);
  if (lastSabrBody) logBig("sabrBody", lastSabrBody);
  callHost("log/capture", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({
      "code": "E20088",
      "message": "PO rejected sps=" + sps,
    });
    if (p) await p;
    p = pipe.sink.close();
    if (p) await p;
    await pipe.source.readUtf8();
  }).then(
    function () {},
    function (e: unknown) {
      console.warn("bg po-reject report skipped: " + (e instanceof Error ? e.message : String(e)));
    },
  );
}

export function recordBgChallenge(p: Record<string, unknown>): void {
  loggedSps = 0;
  reportedReject = false;
  lastSabrUrl = "";
  lastSabrBody = "";
  logField("sessionKey", utcStamp());
  logField("ua", DESKTOP_UA);
  const small = ["eventId", "clientVersion", "globalName", "interpUrl"];
  for (let i = 0; i < small.length; i++) {
    const k = small[i]!;
    const v = p[k];
    if (typeof v === "string" && v) logField(k, v);
  }
  const big = ["program", "snapshot", "integrityToken", "interpJs", "visitorData"];
  for (let i = 0; i < big.length; i++) {
    const k = big[i]!;
    const v = p[k];
    if (typeof v === "string" && v) logBig(k, v);
  }
  dumpEntropy();
}

export function recordBgMint(id: Uint8Array, token: unknown): void {
  logField("mintId", new TextDecoder().decode(id));
  if (token instanceof Uint8Array) logBig("pot", u8ToB64url(token));
  else if (typeof token === "string" && token) logBig("pot", token);
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
  if (sps !== 1) reportPoReject(sps);
}
