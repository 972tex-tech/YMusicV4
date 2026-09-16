// Headless YouTube player: singleton Application over WEB homepage config from main.

import { HttpRequest } from "../../protocol/http";
import { JsEngineErrorImpl, callMain } from "../../protocol/command";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA } from "../yt/session";
import {
  activeHtmlMedia,
  installMseGlobals,
  isLiveDuration,
  mseCreateObjectURL,
  mseRevokeObjectURL,
  mseSourceBufferCount,
  mseMediaSourceId,
  mseSourceBufferGen,
  mseAttachLatestIfStale,
  dumpDrain,
  resetDrainCounts,
} from "../../protocol/mse";
import { installOrigEnv } from "./env";
import { installXhr } from "../yt/xhr";
import { installFetch } from "../yt/fetch";
import { minterFromConfig } from "./pot";
import { installPlayerAdStrip } from "./skip_ads";
import {
  isAudioOnly,
  isLiveVideoRest,
  setAudioOnly,
  setLiveVideoRest,
  setYmQuality,
  ymQuality,
} from "./audio_only";
import {
  captionIngestTick,
  installCaptionMinter,
  onCaptionSeek,
  selectedCaptionVss,
  waitCaptionIngest,
} from "./captions";
import { playerStorageReady } from "./storage";
import { applyViewport, viewportHeight, viewportWidth } from "./viewport";

const ORIGIN = "https://www.youtube.com";
let lastQualityExtra = -1;

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function sizePlayerEl(el: Element | null): void {
  if (!el) return;
  el.clientWidth = el.offsetWidth = viewportWidth();
  el.clientHeight = el.offsetHeight = viewportHeight();
}

function sizeNodeList(list: HTMLCollection | null): void {
  if (!list) return;
  for (let i = 0; i < list.length; i++) sizePlayerEl(list[i] ?? null);
}

function sizePlayerTree(): void {
  sizePlayerEl(document.getElementById("player"));
  sizePlayerEl(document.getElementById("movie_player"));
  sizePlayerEl(document.getElementById("video-player"));
  sizeNodeList(document.getElementsByTagName("video"));
  sizeNodeList(document.getElementsByClassName("html5-video-container"));
  sizeNodeList(document.getElementsByClassName("html5-video-player"));
}

function moviePlayer(): Record<string, unknown> | null {
  const el = document.getElementById("movie_player");
  return el as unknown as Record<string, unknown> | null;
}

/** Same as webview-js player_connect: ytInternalPlayer.setPlaybackQuality, else range(q). */
function pinPlaybackQuality(host: Record<string, unknown> | null, q: string): boolean {
  const g = globalThis as unknown as Record<string, unknown>;
  const internal = asRec(g["ytInternalPlayer"]);
  if (internal && typeof internal["setPlaybackQuality"] === "function") {
    (internal["setPlaybackQuality"] as (quality: string) => void).call(internal, q);
    return true;
  }
  if (!host) return false;
  const range = host["setPlaybackQualityRange"];
  if (typeof range === "function") {
    (range as (quality: string) => void).call(host, q);
    return true;
  }
  const one = host["setPlaybackQuality"];
  if (typeof one === "function") {
    (one as (quality: string) => void).call(host, q);
    return true;
  }
  return false;
}

function wrapGetPlaybackQuality(host: Record<string, unknown> | null): void {
  if (!host || typeof host["getPlaybackQuality"] !== "function") return;
  const orig = host["getPlaybackQuality"] as (...a: unknown[]) => unknown;
  if ((orig as { __ym?: boolean }).__ym) return;
  const wrapped = function (this: unknown) {
    if (isAudioOnly()) return "auto";
    try {
      const q = orig.call(this);
      if (q != null && String(q) !== "") return q;
    } catch (_e) {}
    return "auto";
  };
  (wrapped as { __ym?: boolean }).__ym = true;
  host["getPlaybackQuality"] = wrapped;
}

let pictureW = 0;
let pictureH = 0;

/** First adaptiveFormats width/height — same as webview-js onVideoDataChange → `_aspect_ratio`. */
function applyPictureFromResponse(): boolean {
  const el = moviePlayer();
  const fn = el ? el["getPlayerResponse"] : undefined;
  if (typeof fn !== "function") return false;
  let pr: unknown;
  try {
    pr = (fn as () => unknown).call(el);
  } catch (_e) {
    return false;
  }
  const rec = asRec(pr);
  const sd = rec ? asRec(rec["streamingData"]) : null;
  const formats = sd ? sd["adaptiveFormats"] : null;
  if (!Array.isArray(formats)) return false;
  for (let i = 0; i < formats.length; i++) {
    const fmt = asRec(formats[i]);
    if (!fmt) continue;
    const w = Number(fmt["width"]);
    const h = Number(fmt["height"]);
    if (isFinite(w) && w > 0 && isFinite(h) && h > 0) {
      const iw = Math.round(w);
      const ih = Math.round(h);
      if (pictureW === iw && pictureH === ih) return false;
      pictureW = iw;
      pictureH = ih;
      if (MSE_DEBUG) console.log("mse picture " + iw + "x" + ih);
      return true;
    }
  }
  return false;
}

function bindPictureSize(el: Record<string, unknown>): void {
  if ((el as { __ymPicture?: boolean }).__ymPicture) return;
  (el as { __ymPicture?: boolean }).__ymPicture = true;
  const add = el["addEventListener"];
  if (typeof add !== "function") return;
  (add as (name: string, fn: () => void) => void).call(el, "onVideoDataChange", function () {
    if (applyPictureFromResponse()) notifyProgress();
  });
}

/** Same as webview-js: chip actual = getPlaybackQuality on onPlaybackQualityChange. */
function bindPlaybackQualityChange(el: Record<string, unknown>): void {
  if ((el as { __ymQualityEvt?: boolean }).__ymQualityEvt) return;
  const add = el["addEventListener"];
  if (typeof add !== "function") return;
  (el as { __ymQualityEvt?: boolean }).__ymQualityEvt = true;
  (add as (name: string, fn: () => void) => void).call(el, "onPlaybackQualityChange", function () {
    notifyCatalog();
  });
}

/** App.FC copies IFrame names (state.Y) onto template.element = #movie_player
 *  when create() is given #player. Copy state.Y if FC landed elsewhere. */
function bindMoviePlayerApi(app: YtApp | null): void {
  const el = moviePlayer();
  if (!el || !app) return;
  bindPictureSize(el);
  bindPlaybackQualityChange(el);
  wrapGetPlaybackQuality(el);
  wrapGetPlaybackQuality(app as unknown as Record<string, unknown>);
  if (typeof el["getPlaybackQuality"] === "function") return;
  const rec = app as unknown as Record<string, unknown>;
  for (const k in rec) {
    try {
      const v = rec[k];
      if (!v || typeof v !== "object") continue;
      const st = (v as Record<string, unknown>)["state"] as Record<string, unknown> | undefined;
      const y = st ? (st["Y"] as Record<string, unknown> | undefined) : undefined;
      if (!y || typeof y["getPlaybackQuality"] !== "function") continue;
      for (const name in y) {
        if (Object.prototype.hasOwnProperty.call(y, name)) el[name] = y[name];
      }
      wrapGetPlaybackQuality(el);
      wrapGetPlaybackQuality(y);
      return;
    } catch (_e) {}
  }
}

function absUrl(src: string): string {
  src = src.replace(/\/player_\w+\.vflset/g, "/player_es6.vflset");
  if (src.indexOf("://") >= 0) return src;
  if (src.indexOf("//") === 0) return "https:" + src;
  if (src.charAt(0) === "/") return ORIGIN + src;
  return ORIGIN + "/" + src;
}

function u8ToB64url(u: Uint8Array): string {
  let s = "";
  for (let i = 0; i < u.length; i++) s += String.fromCharCode(u[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function asU8(v: unknown): Uint8Array {
  if (v instanceof Uint8Array) return v;
  if (typeof v === "string") return new TextEncoder().encode(v);
  if (v && typeof v === "object" && (v as ArrayBufferView).buffer)
    return new Uint8Array(
      (v as ArrayBufferView).buffer,
      (v as ArrayBufferView).byteOffset,
      (v as ArrayBufferView).byteLength,
    );
  return new Uint8Array(0);
}

function setLocation(href: string, pathname: string, search: string): void {
  location.href = href;
  location.protocol = "https:";
  location.host = "www.youtube.com";
  location.hostname = "www.youtube.com";
  location.port = "";
  location.pathname = pathname;
  location.search = search;
  location.hash = "";
  location.origin = ORIGIN;
  location.toString = asNative("toString", function (this: Location) {
    return String(this.href || href);
  });
  location.valueOf = asNative("valueOf", function (this: Location) {
    return String(this.href || href);
  });
  origin = ORIGIN;
  document.URL = href;
  document.documentURI = href;
  document.domain = "www.youtube.com";
  document.location = location;
}

function asNative<T extends Function>(name: string, impl: T): T {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl) as unknown as T;
  return impl;
}

/** e2e C URL is a constructor with createObjectURL. Engine URL is `{createObjectURL}` only. */
function installNativeUrl(): void {
  const g = globalThis as unknown as Record<string, unknown>;
  function urlCtor(this: Record<string, unknown>, url: unknown, base?: unknown) {
    const o: Record<string, unknown> = this && this !== globalThis ? this : {};
    let href = String(url == null ? "" : url);
    let b = "";
    if (base != null) {
      b = typeof base === "string" ? base : String((base as Record<string, unknown>)["href"] || "");
    }
    if (href.indexOf("://") < 0) {
      if (href.indexOf("//") === 0) href = "https:" + href;
      else if (href.charAt(0) === "/") href = "https://www.youtube.com" + href;
      else href = (b || "https://www.youtube.com/") + href;
    }
    let protocol = "https:";
    let hostname = "www.youtube.com";
    let pathname = "/";
    let search = "";
    let hash = "";
    const proto = href.indexOf("://");
    if (proto >= 0) {
      protocol = href.slice(0, proto + 1);
      const rest = href.slice(proto + 3);
      const slash = rest.indexOf("/");
      const q = rest.indexOf("?");
      const h = rest.indexOf("#");
      let hostEnd = rest.length;
      if (slash >= 0 && slash < hostEnd) hostEnd = slash;
      if (q >= 0 && q < hostEnd) hostEnd = q;
      if (h >= 0 && h < hostEnd) hostEnd = h;
      hostname = rest.slice(0, hostEnd);
      pathname = slash >= 0 ? rest.slice(slash) : "/";
    } else {
      pathname = href.charAt(0) === "/" ? href : "/" + href;
    }
    const qi = pathname.indexOf("?");
    const hi = pathname.indexOf("#");
    if (hi >= 0) {
      hash = pathname.slice(hi);
      pathname = pathname.slice(0, hi);
    }
    if (qi >= 0) {
      search = pathname.slice(qi);
      pathname = pathname.slice(0, qi);
    }
    o["href"] = href;
    o["protocol"] = protocol;
    o["host"] = hostname;
    o["hostname"] = hostname;
    o["port"] = "";
    o["pathname"] = pathname || "/";
    o["search"] = search;
    o["hash"] = hash;
    o["origin"] = protocol + "//" + hostname;
    o["username"] = "";
    o["password"] = "";
    o["toString"] = function () {
      return String(o["href"]);
    };
    o["toJSON"] = function () {
      return String(o["href"]);
    };
    return o;
  }
  const ctor = asNative("URL", urlCtor) as unknown as Function & Record<string, unknown>;
  ctor.prototype = urlCtor.prototype || {};
  ctor["createObjectURL"] = asNative("createObjectURL", function (obj: unknown) {
    return mseCreateObjectURL(obj as { __id?: number });
  });
  ctor["revokeObjectURL"] = asNative("revokeObjectURL", function (url: unknown) {
    mseRevokeObjectURL(String(url == null ? "" : url));
  });
  g["URL"] = ctor;
}

function installSabrObserver(): void {
  function IntersectionObserver(this: Record<string, unknown>, cb: unknown) {
    this["_cb"] = cb;
  }
  IntersectionObserver.prototype.observe = asNative(
    "observe",
    function (this: Record<string, unknown>, el: unknown) {
      const self = this;
      const rec = el && typeof el === "object" ? (el as Record<string, unknown>) : null;
      const getRect =
        rec && typeof rec["getBoundingClientRect"] === "function"
          ? (rec["getBoundingClientRect"] as () => Record<string, number>)
          : null;
      const vw = viewportWidth();
      const vh = viewportHeight();
      const rect = getRect
        ? getRect.call(rec)
        : { x: 0, y: 0, width: vw, height: vh, top: 0, left: 0, bottom: vh, right: vw };
      const w = Number(rect["width"]) || vw;
      const h = Number(rect["height"]) || vh;
      Promise.resolve().then(function () {
        const cb = self["_cb"];
        if (typeof cb !== "function") return;
        try {
          (cb as Function)(
            [
              {
                target: el,
                isIntersecting: true,
                isVisible: true,
                intersectionRatio: 1,
                time: 0,
                boundingClientRect: rect,
                intersectionRect: {
                  x: 0,
                  y: 0,
                  width: w,
                  height: h,
                  top: 0,
                  left: 0,
                  bottom: h,
                  right: w,
                },
                rootBounds: {
                  x: 0,
                  y: 0,
                  width: 1280,
                  height: 720,
                  top: 0,
                  left: 0,
                  bottom: 720,
                  right: 1280,
                },
              },
            ],
            self,
          );
        } catch (_e) {}
      });
    },
  );
  IntersectionObserver.prototype.unobserve = asNative("unobserve", function () {});
  IntersectionObserver.prototype.disconnect = asNative("disconnect", function () {});
  (globalThis as unknown as Record<string, unknown>)["IntersectionObserver"] = IntersectionObserver;
}

function installPlayerTree(): void {
  if (document.getElementById("player")) return;
  const player = document.createElement("div");
  player.id = "player";
  sizePlayerEl(player);
  document.body.appendChild(player);
}

function fireWindowResize(): void {
  try {
    const fn = (globalThis as unknown as Record<string, unknown>)["dispatchEvent"];
    if (typeof fn === "function") (fn as (ev: unknown) => unknown)({ type: "resize" });
  } catch (_e) {}
}

export function setPlayerViewport(width: unknown, height: unknown): Record<string, unknown> {
  applyViewport(width, height);
  sizePlayerTree();
  fireWindowResize();
  return { "ok": true, "width": viewportWidth(), "height": viewportHeight() };
}

function owlSlot(): string {
  const g = globalThis as unknown as Record<string, unknown>;
  const cfg = asRec(g["ytcfg"]);
  const flags =
    cfg && typeof cfg["get"] === "function"
      ? asRec((cfg["get"] as (k: string) => unknown)("EXPERIMENT_FLAGS"))
      : null;
  if (flags && flags["bg_st_hr"]) return "havuokmhhs-0";
  const perf = asRec(g["performance"]);
  const originMs =
    perf && typeof perf["timeOrigin"] === "number" ? (perf["timeOrigin"] as number) : 0;
  return "havuokmhhs-" + Math.floor(originMs);
}

function enableSharedOwlFlag(): void {
  const g = globalThis as unknown as Record<string, unknown>;
  const cfg = asRec(g["ytcfg"]);
  if (!cfg || typeof cfg["get"] !== "function" || typeof cfg["set"] !== "function") return;
  const get = cfg["get"] as (k: string) => unknown;
  const set = cfg["set"] as (d: unknown) => void;
  const flags = asRec(get("EXPERIMENT_FLAGS")) || {};
  flags["html5_use_shared_owl_instance"] = true;
  flags["html5_webpo_idle_priority_job"] = false;
  // Player fetch gzips /youtubei/v1/player when this is on; jsonObject() then
  // sees gzip magic (0x1f) instead of `{`.
  flags["wug_networking_gzip_request"] = false;
  set({ "EXPERIMENT_FLAGS": flags });
}

function installSharedOwl(mintFn: (id: Uint8Array) => unknown): void {
  const g = globalThis as unknown as Record<string, unknown>;
  enableSharedOwlFlag();
  const nop = function () {};
  const wpcHandle: Record<string, unknown> = {
    "f": function () {
      return Promise.resolve();
    },
    "c": function (n: number) {
      return !(n > 150);
    },
    "m": function (req: unknown) {
      const rec = asRec(req);
      const c = rec ? rec["c"] : undefined;
      const b =
        c instanceof Uint8Array
          ? c
          : typeof c === "string"
            ? new TextEncoder().encode(c)
            : new Uint8Array(0);
      return mintFn(b);
    },
    "mws": function (req: unknown) {
      const raw = asU8((wpcHandle["m"] as (r: unknown) => unknown)(req));
      return u8ToB64url(raw);
    },
  };
  const bevasrs: Record<string, unknown> = {
    "s": nop,
    "e": nop,
    "c": function () {
      return Promise.resolve();
    },
    "p": Promise.resolve(),
    "l": function () {
      return {};
    },
    "wpc": function () {
      return Promise.resolve(wpcHandle);
    },
  };
  const slot = owlSlot();
  let box = asRec(g[slot]);
  if (!box) box = {};
  box["bevasrs"] = bevasrs;
  g[slot] = box;
}

type YtApp = {
  "loadVideoById"?: (id: string, start?: number) => unknown;
  "loadVideoByPlayerVars": (vars: Record<string, unknown>) => unknown;
  "preloadVideoById"?: (id: string) => unknown;
  "preloadVideoByPlayerVars"?: (
    vars: Record<string, unknown>,
    unused?: unknown,
    expireSec?: number,
  ) => unknown;
  "playVideo": () => void;
  "pauseVideo": () => void;
  "seekTo": (t: number, allowSeekAhead?: boolean) => void;
  "setPlaybackQualityRange"?: (q: string, q2?: string) => void;
  "setPlaybackQuality"?: (q: string) => void;
  "getPlaybackQuality"?: () => string;
  "getUserPlaybackQualityPreference"?: () => string;
  "getAvailableQualityData"?: () => unknown;
  "getAvailableQualityLevels"?: () => unknown;
  "getPlayerResponse"?: () => unknown;
};

function parseQueryMap(s: string): Record<string, string> {
  const out: Record<string, string> = {};
  if (!s) return out;
  const parts = s.split("&");
  for (let i = 0; i < parts.length; i++) {
    const p = parts[i]!;
    if (!p) continue;
    const eq = p.indexOf("=");
    if (eq < 0) out[p] = "";
    else out[p.slice(0, eq)] = p.slice(eq + 1);
  }
  return out;
}

function joinQueryMap(map: Record<string, string>): string {
  const keys = Object.keys(map);
  const parts: string[] = [];
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]!;
    parts.push(k + "=" + map[k]);
  }
  return parts.join("&");
}

function patchFlags(s: string, patches: Record<string, string>): string {
  const map = parseQueryMap(s);
  const keys = Object.keys(patches);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]!;
    map[k] = patches[k]!;
  }
  return joinQueryMap(map);
}

function rewritePlayerJsUrls(obj: unknown, depth: number): void {
  if (!obj || typeof obj !== "object" || depth > 6) return;
  const rec = obj as Record<string, unknown>;
  const keys = Object.keys(rec);
  for (let i = 0; i < keys.length; i++) {
    const k = keys[i]!;
    const v = rec[k];
    if (typeof v === "string" && v.indexOf("/player_") >= 0)
      rec[k] = v.replace(/\/player_\w+\.vflset/g, "/player_es6.vflset");
    else if (v && typeof v === "object") rewritePlayerJsUrls(v, depth + 1);
  }
}

async function fetchPlayerConfig(): Promise<Record<string, unknown>> {
  return callMain("get_player_config", async (pipe) => {
    const w = pipe.sink.writeObjectAsJson({});
    if (w) await w;
    const c = pipe.sink.close();
    if (c) await c;
    return await pipe.source.readJsonObject();
  });
}

class Player {
  private player: YtApp | null = null;
  private mintFn: ((id: Uint8Array) => unknown) | null = null;
  private config: Record<string, unknown> | null = null;
  private minterP: Promise<Record<string, unknown>> | null = null;
  private initP: Promise<Record<string, unknown>> | null = null;
  private createFn: ((r: unknown, a: unknown, b: unknown) => unknown) | null = null;
  private createCtx: Record<string, unknown> = {};

  init(): Promise<Record<string, unknown>> {
    if (this.initP == null) {
      this.initP = this.doInit().then(
        (v) => v,
        (e: unknown) => {
          this.initP = null;
          throw e;
        },
      );
    }
    return this.initP;
  }

  private ensureMinter(): Promise<Record<string, unknown>> {
    if (this.minterP == null) {
      this.minterP = this.doMinter().then(
        (v) => v,
        (e: unknown) => {
          this.minterP = null;
          throw e;
        },
      );
    }
    return this.minterP;
  }

  private async doMinter(): Promise<Record<string, unknown>> {
    const out: Record<string, unknown> = { "step": "env" };
    await playerStorageReady();
    installOrigEnv();
    installNativeUrl();
    setLocation(ORIGIN + "/", "/", "");
    out["step"] = "config";
    const cfg = await fetchPlayerConfig();
    this.config = cfg;

    out["step"] = "minter";
    this.mintFn = await minterFromConfig(cfg, out);
    const self = this;
    installCaptionMinter(function (id: string) {
      return self.mintToken(id);
    });
    installMseGlobals();
    installSabrObserver();
    installXhr();
    installFetch();
    installSharedOwl(this.mintFn);
    return out;
  }

  private async doInit(): Promise<Record<string, unknown>> {
    const out = await this.ensureMinter();
    const cfg = this.config;
    if (!cfg) throw new JsEngineErrorImpl("player config missing", "E20075");
    const jsUrlRaw = typeof cfg["jsUrl"] === "string" ? (cfg["jsUrl"] as string) : "";
    if (!jsUrlRaw) throw new JsEngineErrorImpl("player config has no jsUrl", "E20075");

    installPlayerTree();
    installPlayerAdStrip();
    const g = globalThis as unknown as Record<string, unknown>;
    const ytcfg = asRec(g["ytcfg"]);
    if (ytcfg) rewritePlayerJsUrls(ytcfg["data_"] ?? ytcfg, 0);

    const jsUrl = absUrl(jsUrlRaw);
    out["jsUrl"] = jsUrl;
    out["step"] = "basejs";
    const baseRes = await executeWithCookies(
      jsUrl,
      HttpRequest.get(jsUrl)
        .setHeader("User-Agent", DESKTOP_UA)
        .setHeader("Referer", ORIGIN + "/")
        .timeout(60000),
    );
    if (!baseRes.ok) {
      baseRes.ignoreResult();
      throw new JsEngineErrorImpl("base.js HTTP " + baseRes.status, "E20075");
    }
    const baseJs = await baseRes.text();
    out["baseJsLen"] = baseJs.length;
    try {
      (0, eval)(baseJs + "\n//# sourceURL=player_es6_base.js");
    } catch (e) {
      const err = e instanceof Error ? e : new Error(String(e));
      throw new JsEngineErrorImpl(
        "base.js eval: " + err.message + (typeof err.stack === "string" ? "\n" + err.stack : ""),
        "E20075",
        { cause: err },
      );
    }

    const yt = asRec(g["yt"]);
    const playerNs = yt ? asRec(yt["player"]) : null;
    const Application = playerNs ? asRec(playerNs["Application"]) : null;
    const create = Application ? Application["create"] : undefined;
    if (typeof create !== "function")
      throw new JsEngineErrorImpl("yt.player.Application.create missing", "E20076");

    const get = ytcfg ? (ytcfg["get"] as (k: string) => unknown) : null;
    const ctxs = get ? asRec(get("WEB_PLAYER_CONTEXT_CONFIGS")) : null;
    const ctx = ctxs ? asRec(ctxs["WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH"]) : null;
    if (!ctx)
      throw new JsEngineErrorImpl("missing WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH", "E20075");
    enableSharedOwlFlag();
    if (typeof ctx["serializedExperimentFlags"] === "string") {
      ctx["serializedExperimentFlags"] = patchFlags(ctx["serializedExperimentFlags"] as string, {
        "html5_use_shared_owl_instance": "true",
        "html5_webpo_idle_priority_job": "false",
        "wug_networking_gzip_request": "false",
      });
    }
    this.createFn = create as (r: unknown, a: unknown, b: unknown) => unknown;
    this.createCtx = ctx;
    out["step"] = "ok";
    out["ok"] = true;
    return out;
  }

  private ensureCreated(out: Record<string, unknown>): YtApp {
    if (this.player) return this.player;
    const create = this.createFn;
    if (typeof create !== "function")
      throw new JsEngineErrorImpl("yt.player.Application.create missing", "E20076");
    /* No video_id here: create would POST /player + SABR, then load
       would do it again. Watch starts only in loadVideoByPlayerVars. */
    const playerVars: Record<string, unknown> = {
      "skip_ads": 1,
    };
    const param2 = {
      "args": playerVars,
      "attrs": {
        "id": "video-player",
        "width": String(viewportWidth()),
        "height": String(viewportHeight()),
      },
      "loaded": true,
    };
    /* Arg 1 is the parent. Application.create appends a new div whose id is
       ctx.rootElementId ("movie_player") and copies the IFrame API onto it. */
    const root = document.getElementById("player");
    if (!root) throw new JsEngineErrorImpl("missing #player", "E20076");
    sizePlayerEl(root);
    out["step"] = "create";
    try {
      this.player = create(root, param2, this.createCtx) as YtApp;
      (globalThis as unknown as Record<string, unknown>)["__ymPlayer"] = this.player;
      sizePlayerTree();
      fireWindowResize();
      bindMoviePlayerApi(this.player);
      if (MSE_DEBUG) {
        const el = moviePlayer();
        console.log(
          "mse create api movie=" +
            (el && typeof el["getPlaybackQuality"] === "function" ? "1" : "0") +
            " range=" +
            (el && typeof el["setPlaybackQualityRange"] === "function" ? "1" : "0") +
            " levels=" +
            (el && typeof el["getAvailableQualityLevels"] === "function" ? "1" : "0"),
        );
      }
    } catch (e) {
      throw new JsEngineErrorImpl(
        "Application.create: " + (e instanceof Error ? e.message : String(e)),
        "E20076",
        { cause: e instanceof Error ? e : undefined },
      );
    }
    return this.player;
  }

  private async mintToken(videoId: string): Promise<string> {
    const fn = this.mintFn;
    if (!fn) throw new JsEngineErrorImpl("player not initialized", "E20074");
    let minted: unknown = fn(new TextEncoder().encode(videoId));
    if (minted && typeof (minted as Promise<unknown>).then === "function")
      minted = await (minted as Promise<unknown>);
    if (!minted) throw new JsEngineErrorImpl("YNJ:Undefined", "E20074");
    return typeof minted === "string" ? minted : u8ToB64url(asU8(minted));
  }

  setQuality(quality: string): void {
    const q = String(quality || "");
    if (!q) throw new JsEngineErrorImpl("player/setQuality missing quality", "E20085");
    const player = this.player;
    if (!player)
      throw new JsEngineErrorImpl("player/setQuality before Application.create", "E20085");
    setYmQuality(q);
    if (isAudioOnly()) {
      notifyCatalog();
      return;
    }
    if (isLiveVideoRest()) {
      setLiveVideoRest(false);
      __mseRestVideo(0);
    }
    bindMoviePlayerApi(player);
    const pinned =
      pinPlaybackQuality(moviePlayer(), q) ||
      pinPlaybackQuality(player as unknown as Record<string, unknown>, q);
    if (!pinned && MSE_DEBUG) console.log("mse setQuality no API q=" + q);
    if (MSE_DEBUG)
      console.log(
        "mse setQuality q=" + q + " clock=" + (activeHtmlMedia() ? __mseCurrentTime() : 0),
      );
    dumpDrain("setQuality-" + q);
    /* Do not seek. Video SourceBuffer already reports a sliver, so SABR
       fetches video on the next tick without jumping leftover audio. */
    notifyCatalog();
  }

  /** Enter: pin Auto, rest decoder, black. Exit: un-rest, restore remembered quality. */
  setIsAudioOnly(on: boolean): void {
    if (on) {
      setAudioOnly(true);
      this.pinAuto();
      __mseRestVideo(1);
    } else {
      setAudioOnly(false);
      __mseRestVideo(0);
      const q = ymQuality();
      const player = this.player;
      if (player && q) {
        bindMoviePlayerApi(player);
        pinPlaybackQuality(moviePlayer(), q) ||
          pinPlaybackQuality(player as unknown as Record<string, unknown>, q);
      }
    }
    resetDrainCounts();
    if (MSE_DEBUG)
      console.log(
        "mse setIsAudioOnly " + on + " clock=" + (activeHtmlMedia() ? __mseCurrentTime() : 0),
      );
    dumpDrain(on ? "setIsAudioOnly-on" : "setIsAudioOnly-off");
    notifyCatalog();
  }

  /** Auto (not tiny): pinning 144p changeType-interrupts leftover. */
  private pinAuto(): void {
    const player = this.player;
    if (!player) return;
    bindMoviePlayerApi(player);
    pinPlaybackQuality(moviePlayer(), "auto") ||
      pinPlaybackQuality(player as unknown as Record<string, unknown>, "auto");
  }

  private ytHost(): Record<string, unknown> {
    return moviePlayer() || (this.player as unknown as Record<string, unknown>);
  }

  private loadById(videoId: string): void {
    const host = this.ytHost();
    const byId = host["loadVideoById"];
    if (typeof byId === "function") {
      (byId as (id: string) => unknown).call(host, videoId);
      return;
    }
    const byVars = host["loadVideoByPlayerVars"];
    if (typeof byVars === "function") {
      (byVars as (vars: Record<string, unknown>) => unknown).call(host, {
        "video_id": videoId,
        "autoplay": "1",
      });
      return;
    }
    throw new JsEngineErrorImpl("player/load loadVideoById missing", "E20077");
  }

  async load(videoId: string, quality?: string, audioOnly?: boolean): Promise<Record<string, unknown>> {
    const initOut = await this.init();
    const ao = audioOnly === true;
    const q = quality && quality.length ? quality : undefined;
    const out: Record<string, unknown> = {
      "videoId": videoId,
      "quality": ao ? "auto" : q || "auto",
      "audioOnly": ao,
      "jsUrl": initOut["jsUrl"],
      "baseJsLen": initOut["baseJsLen"],
      "ytGets": initOut["ytGets"],
      "step": "loadVideoById",
    };
    pictureW = 0;
    pictureH = 0;
    notifyProgress();
    this.ensureCreated(out);
    /* Chrome /player uses watch URL as originalUrl / currentUrl / graftUrl. */
    setLocation(ORIGIN + "/watch?v=" + videoId, "/watch", "?v=" + videoId);
    if (ao) this.setIsAudioOnly(true);
    else if (q) this.setQuality(q);
    const msId = mseMediaSourceId();
    const gen0 = mseSourceBufferGen();
    const ingestAt = captionIngestTick();
    try {
      this.loadById(videoId);
    } catch (e) {
      throw new JsEngineErrorImpl(
        "loadVideoById: " + (e instanceof Error ? e.message : String(e)),
        "E20077",
        { cause: e instanceof Error ? e : undefined },
      );
    }
    await waitCaptionIngest(ingestAt, 8000);
    const t0 = Date.now();
    const waitMs = ao ? 2000 : 1000;
    while (Date.now() - t0 < waitMs) {
      mseAttachLatestIfStale(msId);
      if (ao) __mseRestVideo(1);
      const id = mseMediaSourceId();
      const n = mseSourceBufferCount();
      if ((id !== msId && n > 0) || mseSourceBufferGen() > gen0) break;
      /* loadVideoById may reuse the open MediaSource (no new SB). */
      if (n > 0 && Date.now() - t0 > 400) break;
      await new Promise<void>(function (resolve) {
        setTimeout(function () {
          resolve(undefined);
        }, 20);
      });
    }
    if (ao) this.setIsAudioOnly(true);
    applyPictureFromResponse();
    notifyProgress();
    notifyCatalog();
    out["step"] = "ok";
    out["ok"] = true;
    return out;
  }

  async preload(videoId: string): Promise<Record<string, unknown>> {
    const initOut = await this.init();
    const out: Record<string, unknown> = {
      "videoId": videoId,
      "jsUrl": initOut["jsUrl"],
      "step": "preload",
    };
    this.ensureCreated(out);
    const host = this.ytHost();
    const byVars = host["preloadVideoByPlayerVars"];
    const byId = host["preloadVideoById"];
    try {
      /* IFrame preloadVideoById is a no-op unless embedsHostFlags.allowPreloadVideoById.
         WEB uses preloadVideoByPlayerVars(vars, undefined, expireSec) like webview-js. */
      if (typeof byVars === "function")
        (byVars as (vars: Record<string, unknown>, u?: unknown, exp?: number) => unknown).call(
          host,
          { "video_id": videoId },
          undefined,
          60,
        );
      else if (typeof byId === "function") (byId as (id: string) => unknown).call(host, videoId);
      else throw new JsEngineErrorImpl("player/preload API missing", "E20092");
    } catch (e) {
      if (e instanceof JsEngineErrorImpl) throw e;
      throw new JsEngineErrorImpl(
        "preload: " + (e instanceof Error ? e.message : String(e)),
        "E20092",
        { cause: e instanceof Error ? e : undefined },
      );
    }
    if (MSE_DEBUG) console.log("mse preload " + videoId);
    out["ok"] = true;
    return out;
  }

  async mintPo(videoId: string): Promise<Record<string, unknown>> {
    const minterOut = await this.ensureMinter();
    const token = await this.mintToken(videoId);
    return {
      "videoId": videoId,
      "contentPoToken": token,
      "contentPoTokenLen": token.length,
      "ytGets": minterOut["ytGets"],
      "eventIdGets": minterOut["ytEventIdGets"],
      "eventId": minterOut["eventId"],
      "programSource": minterOut["programSource"],
      "step": "ok",
      "ok": true,
    };
  }

  play(): void {
    const player = this.player;
    if (!player) throw new JsEngineErrorImpl("player not created", "E20082");
    player["playVideo"]();
  }

  pause(): void {
    const player = this.player;
    if (!player) throw new JsEngineErrorImpl("player not created", "E20083");
    player["pauseVideo"]();
  }

  seek(t: number): void {
    const player = this.player;
    if (!player) throw new JsEngineErrorImpl("player not created", "E20081");
    const ct = activeHtmlMedia() ? __mseCurrentTime() : -1;
    if (MSE_DEBUG) console.log("mse seek fire t=" + t + " ct=" + ct);
    player["seekTo"](t, true);
    onCaptionSeek();
  }

  /** YouTube IFrame getProgressState — DVR allowSeeking / seekableStart / seekableEnd. */
  private progressState(): Record<string, unknown> | null {
    const host = moviePlayer();
    const fn = host && host["getProgressState"];
    if (typeof fn !== "function") return null;
    try {
      const s = (fn as () => unknown).call(host);
      return s && typeof s === "object" ? (s as Record<string, unknown>) : null;
    } catch (_e) {
      return null;
    }
  }

  stop(): void {
    this.pause();
    this.seek(0);
  }

  progress(): Record<string, unknown> {
    const v = activeHtmlMedia();
    const dur = v ? Number(v["duration"]) : NaN;
    let bufferedMs = 0;
    if (v) {
      const b = v["buffered"] as { length?: number; end?: (i: number) => number } | null;
      const n = b && typeof b["length"] === "number" ? Number(b["length"]) : 0;
      if (n > 0 && typeof b!["end"] === "function") {
        const end = Number(b!["end"]!(n - 1));
        if (isFinite(end) && end > 0) bufferedMs = Math.round(end * 1000);
      }
    }
    const pos = v ? Math.round(Number(v["currentTime"]) * 1000) : 0;
    const live = isLiveDuration();
    const sk = v
      ? (v["seekable"] as {
          length?: number;
          start?: (i: number) => number;
          end?: (i: number) => number;
        } | null)
      : null;
    const skN = sk && typeof sk["length"] === "number" ? Number(sk["length"]) : 0;
    let skStart = 0;
    let skEnd = 0;
    if (skN > 0 && typeof sk!["start"] === "function" && typeof sk!["end"] === "function") {
      skStart = Number(sk!["start"]!(0));
      skEnd = Number(sk!["end"]!(skN - 1));
    }
    const ps = this.progressState();
    if (ps) {
      const a = Number(ps["seekableStart"]);
      const b = Number(ps["seekableEnd"]);
      if (isFinite(a)) skStart = a;
      if (isFinite(b) && b > skStart) skEnd = b;
    }
    /* Leftover is not the DVR window. allowSeeking is YouTube's DVR vs head-only. */
    const seekable = ps ? !!ps["allowSeeking"] : !live;
    if (seekable && !(skEnd > skStart + 1)) {
      const ct = v ? Number(v["currentTime"]) : 0;
      if (isFinite(ct) && ct > 0) {
        skStart = 0;
        skEnd = ct;
      }
    }
    const durationMs =
      isFinite(dur) && dur > 0
        ? Math.round(dur * 1000)
        : isFinite(skEnd) && skEnd > 0
          ? Math.round(skEnd * 1000)
          : 0;
    const ended =
      (v && !!v["ended"]) ||
      (!live && durationMs > 0 && pos >= durationMs - 250 && v && !!v["paused"]);
    return {
      "positionMs": pos,
      "durationMs": durationMs,
      "bufferedMs": bufferedMs,
      "live": live,
      "seekable": seekable,
      "seekableStartMs": Math.round((isFinite(skStart) ? skStart : 0) * 1000),
      "seekableEndMs": Math.round((isFinite(skEnd) ? skEnd : 0) * 1000),
      "paused": v ? !!v["paused"] : true,
      "ended": !!ended,
      "readyState": v ? Number(v["readyState"]) : 0,
      "videoWidth": isAudioOnly() ? 0 : pictureW,
      "videoHeight": isAudioOnly() ? 0 : pictureH,
      "audioOnly": isAudioOnly(),
    };
  }

  catalog(): Record<string, unknown> {
    const host = moviePlayer();
    const pref =
      host && typeof host["getUserPlaybackQualityPreference"] === "function"
        ? String((host["getUserPlaybackQualityPreference"] as () => string)())
        : ymQuality();
    const actual =
      host && typeof host["getPlaybackQuality"] === "function"
        ? String((host["getPlaybackQuality"] as () => string)())
        : ymQuality();
    return {
      "quality": actual,
      "preferQuality": pref,
      "audioOnly": isAudioOnly(),
      "qualities": this.qualityOptions(),
      "caption": selectedCaptionVss(),
    };
  }

  status(): Record<string, unknown> {
    const p = this.progress();
    const c = this.catalog();
    const out: Record<string, unknown> = {};
    for (const k in p) out[k] = p[k];
    for (const k in c) out[k] = c[k];
    return out;
  }

  private qualityOptions(): Record<string, unknown>[] {
    const labels: Record<string, string> = {
      "tiny": "144p",
      "small": "240p",
      "medium": "360p",
      "large": "480p",
      "hd720": "720p",
      "hd1080": "1080p",
      "hd1440": "1440p",
      "hd2160": "4K",
      "highres": "8K",
    };
    const rows: Record<string, unknown>[] = [{ "quality": "auto", "label": "Auto" }];
    const seen: Record<string, boolean> = { "auto": true };
    function add(q: string, label?: string): void {
      if (!q || q === "auto" || q === "default") return;
      const lab = label && label.length ? label : labels[q] || q;
      for (let i = 0; i < rows.length; i++) {
        if (String(rows[i]!["label"]) === lab) return;
      }
      seen[q] = true;
      rows.push({ "quality": q, "label": lab });
    }
    bindMoviePlayerApi(this.player);
    const host = moviePlayer();
    let dataN = 0;
    let levelsN = 0;
    let dataLabs = "";
    let levelList = "";
    try {
      if (host && typeof host["getAvailableQualityData"] === "function") {
        const data = (host["getAvailableQualityData"] as () => unknown)();
        const list = Array.isArray(data) ? data : [];
        dataN = list.length;
        const labs: string[] = [];
        for (let i = 0; i < list.length; i++) {
          const o = asRec(list[i]);
          if (!o) continue;
          const q = o["quality"] != null ? String(o["quality"]) : "";
          const lab = o["qualityLabel"] != null ? String(o["qualityLabel"]) : "";
          if (lab) labs.push(lab);
          add(q, lab);
        }
        dataLabs = labs.join(",");
      }
      if (host && typeof host["getAvailableQualityLevels"] === "function") {
        const levels = (host["getAvailableQualityLevels"] as () => unknown)();
        const list = Array.isArray(levels) ? levels : [];
        levelsN = list.length;
        levelList = list.join(",");
        for (let i = 0; i < list.length; i++) add(String(list[i]));
      }
    } catch (_e) {}
    const extra = rows.length - 1;
    if (extra !== lastQualityExtra) {
      lastQualityExtra = extra;
      if (MSE_DEBUG)
        console.log(
          "mse getAvailableQualityData labels=" +
            dataLabs +
            " n=" +
            dataN +
            " levels=" +
            levelList +
            " extra=" +
            extra,
        );
    }
    return rows;
  }
}

const ytPlayer = new Player();

export function initPlayer(): Promise<Record<string, unknown>> {
  return ytPlayer.init();
}

export function loadPlayer(
  videoId: string,
  quality?: string,
  audioOnly?: boolean,
): Promise<Record<string, unknown>> {
  return ytPlayer.load(videoId, quality, audioOnly);
}

export function preloadPlayer(videoId: string): Promise<Record<string, unknown>> {
  return ytPlayer.preload(videoId);
}

export function setPlayerQuality(quality: string): void {
  ytPlayer.setQuality(quality);
}

export function setPlayerIsAudioOnly(on: boolean): void {
  ytPlayer.setIsAudioOnly(on);
}

export function mintPlayerPo(videoId: string): Promise<Record<string, unknown>> {
  return ytPlayer.mintPo(videoId);
}

export function playPlayer(): void {
  ytPlayer.play();
}

export function pausePlayer(): void {
  ytPlayer.pause();
}

export function seekPlayer(t: number): void {
  ytPlayer.seek(t);
}

export function stopPlayer(): void {
  ytPlayer.stop();
}

export function statusPlayer(): Record<string, unknown> {
  return ytPlayer.status();
}

export function progressPlayer(): Record<string, unknown> {
  return ytPlayer.progress();
}

export function catalogPlayer(): Record<string, unknown> {
  return ytPlayer.catalog();
}

let catalogHook: (() => void) | null = null;
let progressHook: (() => void) | null = null;

export function setCatalogHook(fn: (() => void) | null): void {
  catalogHook = fn;
}

export function setProgressHook(fn: (() => void) | null): void {
  progressHook = fn;
}

function notifyCatalog(): void {
  if (catalogHook) catalogHook();
}

function notifyProgress(): void {
  if (progressHook) progressHook();
}
