// Headless YouTube player: singleton Application over WEB homepage config from main.

import { HttpRequest } from "../../protocol/http";
import { JsEngineErrorImpl, callMain } from "../../protocol/command";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA } from "../yt/session";
import { activeHtmlMedia, installMseGlobals, mseCreateObjectURL, mseRevokeObjectURL, resetMse } from "../../protocol/mse";
import { installOrigEnv } from "./env";
import { installXhr } from "../yt/xhr";
import { installFetch } from "../yt/fetch";
import { minterFromConfig } from "./pot";
import { installPlayerAdStrip } from "./skip_ads";
import { playerStorageReady } from "./storage";

const ORIGIN = "https://www.youtube.com";

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
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
  location.toString = function () {
    return href;
  };
  location.valueOf = function () {
    return href;
  };
  origin = ORIGIN;
  document.URL = href;
  document.documentURI = href;
  document.domain = "www.youtube.com";
  document.location = location;
}

function asNative(name: string, impl: Function): Function {
  if (typeof __nativeFn === "function") return __nativeFn(name, impl);
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
  const ctor = asNative("URL", urlCtor) as Function & Record<string, unknown>;
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
      const rect = getRect
        ? getRect.call(rec)
        : { x: 0, y: 0, width: 640, height: 360, top: 0, left: 0, bottom: 360, right: 640 };
      const w = Number(rect["width"]) || 640;
      const h = Number(rect["height"]) || 360;
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
  const movie = document.createElement("div");
  const wrap = document.createElement("div");
  const video = document.createElement("video");
  player.id = "player";
  movie.id = "movie_player";
  movie.className = "html5-video-player";
  wrap.className = "html5-video-container";
  video.id = "player-html5-video";
  video.className = "video-stream html5-main-video";
  player.clientWidth = player.offsetWidth = 640;
  player.clientHeight = player.offsetHeight = 360;
  movie.clientWidth = movie.offsetWidth = 640;
  movie.clientHeight = movie.offsetHeight = 360;
  wrap.appendChild(video);
  movie.appendChild(wrap);
  player.appendChild(movie);
  document.body.appendChild(player);
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
  "loadVideoByPlayerVars": (vars: Record<string, unknown>) => unknown;
  "playVideo": () => void;
  "pauseVideo": () => void;
  "seekTo": (t: number, allowSeekAhead?: boolean) => void;
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
    if (typeof ctx["serializedExperimentFlags"] === "string") {
      ctx["serializedExperimentFlags"] = patchFlags(
        ctx["serializedExperimentFlags"] as string,
        {
          "html5_use_shared_owl_instance": "true",
          "html5_webpo_idle_priority_job": "false",
        },
      );
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
      "attrs": { "id": "video-player", "width": "640", "height": "360" },
      "loaded": true,
    };
    const root = document.getElementById("player");
    if (!root) throw new JsEngineErrorImpl("missing #player", "E20076");
    out["step"] = "create";
    try {
      this.player = create(root, param2, this.createCtx) as YtApp;
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

  async load(videoId: string): Promise<Record<string, unknown>> {
    const initOut = await this.init();
    const out: Record<string, unknown> = {
      "videoId": videoId,
      "jsUrl": initOut["jsUrl"],
      "baseJsLen": initOut["baseJsLen"],
      "ytGets": initOut["ytGets"],
      "step": "loadVideoByPlayerVars",
    };
    if (this.player) {
      this.pause();
      await resetMse();
    }
    const player = this.ensureCreated(out);
    /* Chrome /player uses watch URL as originalUrl / currentUrl / graftUrl. Create
     * stays without video_id so this is the only /player POST. */
    setLocation(ORIGIN + "/watch?v=" + videoId, "/watch", "?v=" + videoId);
    out["step"] = "loadVideoByPlayerVars";
    try {
      player["loadVideoByPlayerVars"]({
        "video_id": videoId,
        "autoplay": "1",
        "start": 0,
      });
    } catch (e) {
      throw new JsEngineErrorImpl(
        "loadVideoByPlayerVars: " + (e instanceof Error ? e.message : String(e)),
        "E20077",
        { cause: e instanceof Error ? e : undefined },
      );
    }
    out["step"] = "ok";
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
    player["seekTo"](t, true);
  }

  stop(): void {
    this.pause();
    this.seek(0);
  }

  status(): Record<string, unknown> {
    const v = activeHtmlMedia();
    const dur = v ? Number(v["duration"]) : NaN;
    return {
      "positionMs": v ? Math.round(Number(v["currentTime"]) * 1000) : 0,
      "durationMs": isFinite(dur) && dur > 0 ? Math.round(dur * 1000) : 0,
      "paused": v ? !!v["paused"] : true,
      "readyState": v ? Number(v["readyState"]) : 0,
      "videoWidth": v ? Number(v["videoWidth"]) : 0,
      "videoHeight": v ? Number(v["videoHeight"]) : 0,
    };
  }
}

const ytPlayer = new Player();

export function initPlayer(): Promise<Record<string, unknown>> {
  return ytPlayer.init();
}

export function loadPlayer(videoId: string): Promise<Record<string, unknown>> {
  return ytPlayer.load(videoId);
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
