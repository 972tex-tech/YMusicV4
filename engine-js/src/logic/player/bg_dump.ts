// Hold the last homepage miller so a later harness run can: (1) reload the
// program, (2) mint a content PO, (3) POST the captured SABR body and read SPS.
// Console lines are 3k chunks (console.log caps at 8192). Playground reassembles
// them into build/bg-dump/.

import { DESKTOP_UA } from "../yt/session";

const held: Record<string, unknown> = {};
let dumpedSps = 0;
let dumpedPot = false;
let dumpedSabr = false;
let dumpedFirstSps = 0;

function u8ToB64url(u: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
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
  dumpedPot = false;
  dumpedSabr = false;
  dumpedFirstSps = 0;
}

export function recordBgMint(id: Uint8Array, token: unknown): void {
  held["mintId"] = new TextDecoder().decode(id);
  if (token instanceof Uint8Array) held["pot"] = u8ToB64url(token);
  else if (typeof token === "string") held["pot"] = token;
  else held["pot"] = "";
  dumpedSps = 0;
  if (!dumpedPot && held["pot"]) {
    dumpedPot = true;
    console.log("bg dump mintId " + held["mintId"]);
    logBig("bg dump pot", String(held["pot"]));
  }
}

/** Last /videoplayback POST — replay this body with a new PO to read SPS. */
export function recordSabrForDump(url: string, body: Uint8Array | null): void {
  held["sabrUrl"] = url;
  held["sabrBody"] = body && body.length ? u8ToB64url(body) : "";
  if (dumpedSabr) return;
  dumpedSabr = true;
  dumpSabrCapture();
}

/** console.log caps at 8192; terminals often drop ~4k+ lines — stay under 3k. */
const DUMP_CHUNK = 3000;

function logBig(tag: string, s: string): void {
  if (!s.length) {
    console.log(tag + " 0/1 ");
    return;
  }
  const parts = Math.ceil(s.length / DUMP_CHUNK);
  for (let i = 0; i < parts; i++) {
    console.log(tag + " " + i + "/" + parts + " " + s.slice(i * DUMP_CHUNK, i * DUMP_CHUNK + DUMP_CHUNK));
  }
}

function dumpSabrCapture(): void {
  const url = String(held["sabrUrl"] || "");
  const body = String(held["sabrBody"] || "");
  console.log(
    "bg dump sabr meta " +
      JSON.stringify({
        "urlLen": url.length,
        "bodyLen": body.length,
        "mintId": held["mintId"] || "",
        "potLen": String(held["pot"] || "").length,
      }),
  );
  logBig("bg dump sabrUrl", url);
  logBig("bg dump sabrBody", body);
}

export function dumpBgParams(why: string): void {
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
  };
  console.log("bg dump meta " + JSON.stringify(rec));
  console.log("bg dump eventId " + String(held["eventId"] || ""));
  console.log("bg dump interpUrl " + String(held["interpUrl"] || ""));
  logBig("bg dump visitorData", String(held["visitorData"] || ""));
  logBig("bg dump program", String(held["program"] || ""));
  logBig("bg dump snapshot", String(held["snapshot"] || ""));
  logBig("bg dump integrityToken", String(held["integrityToken"] || ""));
  if (held["interpJs"]) logBig("bg dump interpJs", String(held["interpJs"]));
  if (held["pot"]) logBig("bg dump pot", String(held["pot"]));
}

export function dumpFirstSps(sps: number): void {
  if (sps <= 0) return;
  if (dumpedFirstSps !== 0) return;
  dumpedFirstSps = sps;
  console.log("bg dump sps " + sps);
}

/** First sps=2/3 for this mint — full challenge again (mint dump may have lacked pot/SABR). */
export function dumpBgOnSps(sps: number): void {
  dumpFirstSps(sps);
  if (sps !== 2 && sps !== 3) return;
  if (dumpedSps === sps) return;
  dumpedSps = sps;
  dumpBgParams("sps=" + sps);
  if (held["sabrUrl"]) dumpSabrCapture();
}
