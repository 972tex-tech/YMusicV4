// Headless content-PO minter: homepage ytcfg + ytAtN program (not WAA Create),
// set window.yt EVENT_ID, load interpreter, snapshot (must read yt.config_.EVENT_ID), GenerateIT, mint.

import { JsEngineErrorImpl } from "../../protocol/command";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA } from "../yt/session";
import { HttpRequest } from "../../protocol/http";
import { freezeYtAccessCounts, installYtcfg, ytEventIdGets, ytWindowGets } from "./env";
import { dumpBgParams, recordBgChallenge, recordBgMint } from "./bg_dump";

const REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
const API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw";
const GIT_URL = "https://jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT";

function waaHeaders(): Record<string, string> {
  return {
    "content-type": "application/json+protobuf",
    "x-goog-api-key": API_KEY,
    "x-user-agent": "grpc-web-javascript/0.1",
  };
}

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function b64ToU8(s: string): Uint8Array {
  const bin = atob(s);
  const u = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) u[i] = bin.charCodeAt(i);
  return u;
}

function unwrapTru(v: unknown): string {
  if (typeof v === "string") return v;
  if (v && typeof v === "object") {
    const o = v as Record<string, unknown>;
    const w =
      o["privateDoNotAccessOrElseTrustedResourceUrlWrappedValue"] ??
      o["privateDoNotAccessOrElseSafeScriptWrappedValue"];
    if (typeof w === "string") return w;
  }
  return "";
}

function absUrl(src: string): string {
  if (src.indexOf("://") >= 0) return src;
  if (src.indexOf("//") === 0) return "https:" + src;
  if (src.charAt(0) === "/") return "https://www.youtube.com" + src;
  return src;
}

async function waaJson(url: string, body: string): Promise<{ status: number; json: unknown[] }> {
  const r = await HttpRequest.post(url)
    .headers(waaHeaders())
    .setHeader("User-Agent", DESKTOP_UA)
    .setHeader("Origin", "https://www.youtube.com")
    .setHeader("Referer", "https://www.youtube.com/")
    .setHeader("Accept", "*/*")
    .body(body)
    .timeout(30000)
    .build()
    .execute();
  return { "status": r.status, "json": await r.jsonArray() };
}

/** Content-PO minter from main's `get_player_config` payload. HttpRequest only — never fetch. */
export async function minterFromConfig(
  cfg: Record<string, unknown>,
  out: Record<string, unknown>,
): Promise<(id: Uint8Array) => unknown> {
  if (typeof __bgEntropyStart === "function") __bgEntropyStart();
  const ytcfgData = asRec(cfg["ytcfg"]) || {};
  const eventId = typeof cfg["eventId"] === "string" ? (cfg["eventId"] as string) : "";
  if (eventId && ytcfgData["EVENT_ID"] === undefined) ytcfgData["EVENT_ID"] = eventId;
  out["visitorData"] =
    typeof ytcfgData["VISITOR_DATA"] === "string" ? ytcfgData["VISITOR_DATA"] : "";
  out["clientVersion"] =
    typeof ytcfgData["INNERTUBE_CLIENT_VERSION"] === "string"
      ? ytcfgData["INNERTUBE_CLIENT_VERSION"]
      : typeof ytcfgData["INNERTUBE_CONTEXT_CLIENT_VERSION"] === "string"
        ? ytcfgData["INNERTUBE_CONTEXT_CLIENT_VERSION"]
        : "";
  out["apiKey"] =
    typeof ytcfgData["INNERTUBE_API_KEY"] === "string" ? ytcfgData["INNERTUBE_API_KEY"] : "";
  if (!cfg["keepYtcfg"]) installYtcfg(ytcfgData);
  out["ytcfgKeys"] = Object.keys(ytcfgData).length;
  out["eventId"] = typeof ytcfgData["EVENT_ID"] === "string" ? ytcfgData["EVENT_ID"] : eventId;

  const bg = asRec(cfg["bgChallenge"]);
  if (!bg || typeof bg["program"] !== "string")
    throw JsEngineErrorImpl.parse("player config has no bgChallenge.program");
  const program = bg["program"];
  const globalName = String(bg["globalName"] ?? "trayride");
  out["programSource"] = "homepage";
  out["globalName"] = globalName;
  out["programLen"] = typeof program === "string" ? program.length : 0;

  out["step"] = "load";
  let vm = (globalThis as unknown as Record<string, unknown>)[globalName] as
    Record<string, unknown> | undefined;
  if (!vm || typeof vm["a"] !== "function") {
    let js = unwrapTru(bg["interpreterJavascript"]);
    let interpUrl = unwrapTru(bg["interpreterUrl"]) || unwrapTru(bg["interpreterSafeUrl"]);
    if (!js && interpUrl) {
      interpUrl = absUrl(interpUrl);
      const ir = await executeWithCookies(
        interpUrl,
        HttpRequest.get(interpUrl).setHeader("User-Agent", DESKTOP_UA).timeout(30000),
      );
      if (!ir.ok) {
        ir.ignoreResult();
        throw JsEngineErrorImpl.network("interpreter HTTP " + ir.status);
      }
      js = await ir.text();
    }
    if (!js) throw new JsEngineErrorImpl("no interpreter javascript", "E20074");
    if (js.indexOf("//#") === 0) js = js.slice(js.indexOf("\n") + 1);
    out["jsLen"] = js.length;
    (0, eval)(js);
    vm = (globalThis as unknown as Record<string, unknown>)[globalName] as
      Record<string, unknown> | undefined;
  } else {
    out["jsLen"] = 0;
  }
  const vmA = vm && vm["a"];
  if (typeof vmA !== "function") throw new JsEngineErrorImpl("VM missing " + globalName, "E20074");

  out["step"] = "init";
  const fns: Record<string, unknown> = {};
  let setupDone: () => void = function () {};
  const setupWait = new Promise<void>(function (resolve) {
    setupDone = resolve;
  });
  const setup = function (
    asyncSnap: unknown,
    shutdown: unknown,
    passEvent: unknown,
    checkCamera: unknown,
  ) {
    fns["asyncSnap"] = asyncSnap;
    fns["shutdown"] = shutdown;
    fns["passEvent"] = passEvent;
    fns["checkCamera"] = checkCamera;
    setupDone();
  };
  const nop = function () {};
  let ret = (vmA as (...args: unknown[]) => unknown)(
    program,
    setup,
    true,
    undefined,
    nop,
    [[], []],
    undefined,
    false,
    [nop, nop, nop, nop, nop],
  );
  if (ret && typeof (ret as Promise<unknown>).then === "function")
    ret = await (ret as Promise<unknown>);
  if (typeof fns["asyncSnap"] !== "function") {
    await Promise.race([
      setupWait,
      new Promise<void>(function (resolve) {
        setTimeout(function () {
          resolve();
        }, 15000);
      }),
    ]);
  }
  const asyncSnap = fns["asyncSnap"];
  if (typeof asyncSnap !== "function")
    throw new JsEngineErrorImpl("asyncSnapshotFunction missing", "E20074");
  await new Promise(function (resolve) {
    setTimeout(resolve, 50);
  });

  out["step"] = "snapshot";
  const webPo: unknown[] = [];
  if (typeof __bgEntropyBegin === "function") __bgEntropyBegin();
  let botguardResponse: unknown;
  try {
    botguardResponse = await new Promise(function (resolve, reject) {
      try {
        (asyncSnap as (cb: (r: unknown) => void, args: unknown[]) => void)(
          function (resp: unknown) {
            resolve(resp);
          },
          [undefined, undefined, webPo, undefined],
        );
      } catch (e) {
        reject(e);
      }
    });
  } finally {
    if (typeof __bgEntropyEnd === "function") __bgEntropyEnd();
  }
  out["snapshotLen"] = typeof botguardResponse === "string" ? botguardResponse.length : 0;
  out["snapshotPrefix"] =
    typeof botguardResponse === "string" ? (botguardResponse as string).slice(0, 48) : "";
  console.log("mill snap " + out["snapshotLen"] + " " + out["snapshotPrefix"]);
  out["webPo0"] = typeof webPo[0];
  out["webPoLen"] = webPo.length;
  out["ytGets"] = ytWindowGets();
  out["ytEventIdGets"] = ytEventIdGets();
  freezeYtAccessCounts();
  if (!cfg["keepYtcfg"] && ytWindowGets() === 0)
    throw new JsEngineErrorImpl(
      "window.yt was not accessed snap=" + out["snapshotLen"] + " webPo=" + out["webPo0"],
      "E20074",
    );
  if (typeof webPo[0] !== "function")
    throw new JsEngineErrorImpl(
      "webPo minter missing snap=" + out["snapshotLen"] + " ytGets=" + ytEventIdGets(),
      "E20074",
    );

  out["step"] = "generateIT";
  const git = await waaJson(GIT_URL, JSON.stringify([REQUEST_KEY, botguardResponse]));
  out["gitStatus"] = git["status"];
  const gitArr = git["json"];
  const integrityToken = gitArr && gitArr[0];
  out["integrityTokenLen"] = typeof integrityToken === "string" ? integrityToken.length : 0;
  const itBytes = typeof integrityToken === "string" ? b64ToU8(integrityToken).length : 0;
  out["integrityTokenBytes"] = itBytes;
  console.log(
    "minter GenerateIT status=" +
      git["status"] +
      " ITstr=" +
      out["integrityTokenLen"] +
      " ITbytes=" +
      itBytes +
      " snap=" +
      out["snapshotLen"],
  );
  if (typeof integrityToken !== "string" || !integrityToken) {
    throw new JsEngineErrorImpl("Could not get integrity token", "E20074");
  }

  recordBgChallenge({
    "eventId": out["eventId"],
    "visitorData": out["visitorData"],
    "clientVersion": out["clientVersion"],
    "globalName": globalName,
    "interpUrl": unwrapTru(bg["interpreterUrl"]) || unwrapTru(bg["interpreterSafeUrl"]),
    "program": program,
    "snapshot": typeof botguardResponse === "string" ? botguardResponse : "",
    "integrityToken": integrityToken,
    "programLen": out["programLen"],
    "jsLen": out["jsLen"],
    "snapLen": out["snapshotLen"],
    "itLen": out["integrityTokenLen"],
  });
  if (!unwrapTru(bg["interpreterUrl"]) && !unwrapTru(bg["interpreterSafeUrl"])) {
    const js = unwrapTru(bg["interpreterJavascript"]);
    if (js) recordBgChallenge({ "interpJs": js });
  }
  dumpBgParams("mint");

  out["step"] = "mint";
  let mintCallback = (webPo[0] as (t: Uint8Array) => unknown)(b64ToU8(integrityToken));
  /* Await a Promise, not a function that happens to have .then (thenable assimilation). */
  if (
    mintCallback &&
    typeof mintCallback !== "function" &&
    typeof (mintCallback as Promise<unknown>).then === "function"
  ) {
    mintCallback = await (mintCallback as Promise<unknown>);
  }
  if (typeof mintCallback !== "function") throw new JsEngineErrorImpl("APF:Failed", "E20074");
  const mint = mintCallback as (id: Uint8Array) => unknown;
  /* Wrap: async return of a .then-bearing function is Promise-assimilated to undefined. */
  return function millContentPo(id: Uint8Array) {
    const token = mint(id);
    function done(t: unknown): unknown {
      recordBgMint(id, t);
      return t;
    }
    if (token instanceof Uint8Array || typeof token === "string") return done(token);
    if (
      token &&
      typeof token !== "function" &&
      typeof (token as Promise<unknown>).then === "function"
    ) {
      return (token as Promise<unknown>).then(done);
    }
    return done(token);
  };
}
