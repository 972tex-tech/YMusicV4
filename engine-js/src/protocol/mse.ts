// MSE — base.js talks to MediaSource / SourceBuffer / HTMLMediaElement as if this were Chrome.
// Append/play/seek hit C trampolines (`__mse*`). Quoted names so Closure ADVANCED leaves the
// DOM-shaped API intact for YouTube's player.

type Listener = (ev?: Record<string, unknown>) => void;

function mseLog(s: string): void {
  if (!MSE_DEBUG) return;
  console.log(s);
}

function audioFlag(mime: string): number {
  return String(mime).toLowerCase().indexOf("audio/") === 0 ? 1 : 0;
}

function asU8(data: ArrayBuffer | ArrayBufferView): Uint8Array {
  if (data instanceof Uint8Array) return data;
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  }
  return new Uint8Array(data);
}

function fire(target: { _listeners: Record<string, Listener[]> }, type: string): void {
  const list = target._listeners[type];
  if (!list) return;
  const ev: Record<string, unknown> = {
    "type": type,
    "target": target,
    "currentTarget": target,
    "bubbles": false,
    "cancelable": false,
    "defaultPrevented": false,
  };
  for (const fn of list.slice()) {
    try {
      fn.call(target, ev);
    } catch (_e) {}
  }
}

let nextId = 1;

class SourceBuffer {
  ["mimeType"]: string;
  ["id"]: number;
  ["updating"]: boolean;
  ["timestampOffset"]: number;
  ["appendWindowStart"]: number;
  ["appendWindowEnd"]: number;
  _listeners: Record<string, Listener[]>;

  constructor(mime: string) {
    this["mimeType"] = mime;
    this["id"] = nextId++;
    this["updating"] = false;
    this["timestampOffset"] = 0;
    this["appendWindowStart"] = 0;
    this["appendWindowEnd"] = Infinity;
    this._listeners = {};
  }

  ["addEventListener"](type: string, fn: Listener): void {
    const bag = this._listeners;
    (bag[type] || (bag[type] = [])).push(fn);
  }

  ["removeEventListener"](type: string, fn: Listener): void {
    const list = this._listeners[type];
    if (!list) return;
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }

  get ["buffered"](): { length: number; start(i: number): number; end(i: number): number } {
    const r = __mseBuffered(audioFlag(this["mimeType"]));
    const ranges: Array<[number, number]> = [];
    if (r) {
      for (let i = 0; i + 1 < r.length; i += 2) ranges.push([r[i]!, r[i + 1]!]);
    }
    return makeTimeRanges(ranges);
  }

  ["appendBuffer"](data: ArrayBuffer | ArrayBufferView): void {
    if (this["updating"]) throw new TypeError("SourceBuffer.appendBuffer while updating");
    this["updating"] = true;
    fire(this, "updatestart");
    const self = this;
    const u8 = asU8(data);
    const audio = audioFlag(this["mimeType"]);
    const tsOff = self["timestampOffset"];
    const winStart = self["appendWindowStart"];
    const winEnd = self["appendWindowEnd"] === Infinity ? 1e300 : self["appendWindowEnd"];
    __mseAppend(audio, u8, tsOff, winStart, winEnd);
    if (MSE_DEBUG) {
      const br = __mseBuffered(audio);
      mseLog(
        "mse append a=" +
          audio +
          " bytes=" +
          u8.length +
          " tsOff=" +
          tsOff +
          " win=" +
          winStart +
          ".." +
          winEnd +
          " br=" +
          (br ? Array.prototype.join.call(br, ",") : ""),
      );
    }
    Promise.resolve().then(function () {
      self["updating"] = false;
      fire(self, "update");
      fire(self, "updateend");
    });
  }

  ["remove"](start: number, end: number): void {
    if (this["updating"]) throw new TypeError("SourceBuffer.remove while updating");
    this["updating"] = true;
    fire(this, "updatestart");
    const self = this;
    const audio = audioFlag(this["mimeType"]);
    __mseRemove(audio, start, end);
    mseLog("mse remove a=" + audio + " " + start + ".." + end);
    Promise.resolve().then(function () {
      self["updating"] = false;
      fire(self, "update");
      fire(self, "updateend");
    });
  }

  ["abort"](): void {
    __mseAbort(audioFlag(this["mimeType"]));
    this["updating"] = false;
    fire(this, "abort");
    fire(this, "updateend");
  }
}

function makeTimeRanges(ranges: Array<[number, number]>): {
  length: number;
  start(i: number): number;
  end(i: number): number;
} {
  const r = ranges || [];
  return {
    get ["length"]() {
      return r.length;
    },
    "start": function (i: number) {
      return r[i]![0];
    },
    "end": function (i: number) {
      return r[i]![1];
    },
  };
}

class MediaSource {
  ["readyState"]: string;
  ["sourceBuffers"]: SourceBuffer[];
  _duration: number;
  _listeners: Record<string, Listener[]>;
  __id: number;

  constructor() {
    this["readyState"] = "closed";
    this._duration = NaN;
    this["sourceBuffers"] = [];
    this._listeners = {};
    this.__id = nextId++;
    mseLog("mse MediaSource create");
  }

  get ["duration"](): number {
    return this._duration;
  }
  set ["duration"](v: number) {
    this._duration = v;
    if (isFinite(v) && v > 0) __mseSetDuration(v);
  }

  static ["isTypeSupported"](mime: string): boolean {
    const m = String(mime).toLowerCase();
    return (
      m.indexOf("audio/webm") === 0 ||
      m.indexOf("video/webm") === 0 ||
      m.indexOf("audio/mp4") === 0 ||
      m.indexOf("video/mp4") === 0 ||
      m === "audio/mpeg"
    );
  }

  ["addSourceBuffer"](mime: string): SourceBuffer {
    if (this["readyState"] !== "open") {
      const err = new Error("MediaSource is not open");
      err.name = "InvalidStateError";
      throw err;
    }
    mseLog("mse addSourceBuffer " + mime);
    const sb = new SourceBuffer(mime);
    this["sourceBuffers"].push(sb);
    return sb;
  }

  ["endOfStream"](): void {
    this["readyState"] = "ended";
  }

  ["addEventListener"](type: string, fn: Listener): void {
    const bag = this._listeners;
    (bag[type] || (bag[type] = [])).push(fn);
  }

  ["removeEventListener"](type: string, fn: Listener): void {
    const list = this._listeners[type];
    if (!list) return;
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }
}

const blobUrls: Record<string, MediaSource> = {};

let activeMedia: HTMLMediaElement | null = null;
let mediaTick: ReturnType<typeof setInterval> | null = null;

function stopMediaTick(): void {
  if (mediaTick != null) {
    clearInterval(mediaTick);
    mediaTick = null;
  }
}

function startMediaTick(el: HTMLMediaElement): void {
  stopMediaTick();
  mediaTick = setInterval(function () {
    if (!el["paused"]) fire(el, "timeupdate");
  }, 250);
}

function emptyTrackList(): Record<string, unknown> {
  const nop = function () {};
  const list: Record<string, unknown> = {
    "length": 0,
    "addEventListener": nop,
    "removeEventListener": nop,
    "addTextTrack": function () {
      return {};
    },
  };
  return list;
}

function initHtmlMedia(self: HTMLMediaElement): void {
  self["paused"] = true;
  self["ended"] = false;
  self["seeking"] = false;
  self["muted"] = false;
  self["volume"] = 1;
  self["playbackRate"] = 1;
  self["readyState"] = 0;
  self["networkState"] = 0;
  self["videoWidth"] = 640;
  self["videoHeight"] = 360;
  self._src = "";
  self._ms = null;
  self._listeners = {};
  (self as unknown as Record<string, unknown>)["textTracks"] = emptyTrackList();
  (self as unknown as Record<string, unknown>)["audioTracks"] = emptyTrackList();
  (self as unknown as Record<string, unknown>)["videoTracks"] = emptyTrackList();
  activeMedia = self;
}

class HTMLMediaElement {
  ["paused"]!: boolean;
  ["ended"]!: boolean;
  ["seeking"]!: boolean;
  ["muted"]!: boolean;
  ["volume"]!: number;
  ["playbackRate"]!: number;
  ["readyState"]!: number;
  ["networkState"]!: number;
  ["videoWidth"]!: number;
  ["videoHeight"]!: number;
  _src!: string;
  _ms!: MediaSource | null;
  _listeners!: Record<string, Listener[]>;

  constructor() {
    initHtmlMedia(this);
  }

  ["addEventListener"](type: string, fn: Listener): void {
    const bag = this._listeners;
    (bag[type] || (bag[type] = [])).push(fn);
  }

  ["removeEventListener"](type: string, fn: Listener): void {
    const list = this._listeners[type];
    if (!list) return;
    const i = list.indexOf(fn);
    if (i >= 0) list.splice(i, 1);
  }

  ["canPlayType"](t: string): string {
    t = String(t || "").toLowerCase();
    return t.indexOf("webm") >= 0 || t.indexOf("mp4") >= 0 ? "probably" : "";
  }

  ["load"](): void {}

  ["play"](): Promise<void> {
    const self = this;
    this["paused"] = false;
    this["ended"] = false;
    this["readyState"] = 3;
    __msePlay();
    startMediaTick(self);
    return Promise.resolve().then(function () {
      fire(self, "play");
      fire(self, "playing");
    });
  }

  ["pause"](): void {
    this["paused"] = true;
    const self = this;
    stopMediaTick();
    __msePause();
    Promise.resolve().then(function () {
      fire(self, "pause");
    });
  }

  get ["src"](): string {
    return this._src;
  }
  set ["src"](url: string) {
    const prev = this._ms;
    this._src = url;
    const ms = blobUrls[url] || null;
    this._ms = ms;
    mseLog("mse video.src " + url + (ms ? " mse" : " not-mse"));
    if (prev && prev !== ms && prev["readyState"] === "open") {
      prev["readyState"] = "closed";
      fire(prev, "sourceclose");
      fire(prev, "webkitsourceclose");
    }
    if (ms && ms["readyState"] === "closed") {
      ms["readyState"] = "open";
      fire(ms, "sourceopen");
      fire(ms, "webkitsourceopen");
      mseLog("mse sourceopen");
    }
    this["readyState"] = 1;
    const self = this;
    Promise.resolve().then(function () {
      fire(self, "loadstart");
      fire(self, "loadedmetadata");
      fire(self, "durationchange");
    });
  }

  get ["currentSrc"](): string {
    return this._src;
  }

  get ["duration"](): number {
    const ms = this._ms;
    if (ms && typeof ms["duration"] === "number") {
      if (ms["duration"] === Infinity) return Infinity;
      if (isFinite(ms["duration"]) && ms["duration"] > 0) return ms["duration"];
    }
    const d = __mseDuration();
    return d > 0 ? d : NaN;
  }
  set ["duration"](v: number) {
    if (this._ms) this._ms["duration"] = v;
    if (isFinite(v) && v > 0) __mseSetDuration(v);
  }

  get ["buffered"](): { length: number; start(i: number): number; end(i: number): number } {
    const ms = this._ms;
    if (!ms) return makeTimeRanges([]);
    const bufs = ms["sourceBuffers"];
    const all: Array<[number, number]> = [];
    for (let i = 0; i < bufs.length; i++) {
      const br = bufs[i]!["buffered"];
      for (let j = 0; j < br["length"]; j++) all.push([br["start"](j), br["end"](j)]);
    }
    return makeTimeRanges(all);
  }

  get ["seekable"](): { length: number; start(i: number): number; end(i: number): number } {
    /* MSE: finite duration → [0, duration]; +Infinity / NaN → buffered. */
    const d = this["duration"];
    if (isFinite(d) && d > 0) return makeTimeRanges([[0, d]]);
    return this["buffered"];
  }

  get ["played"](): { length: number; start(i: number): number; end(i: number): number } {
    const t = Number(this["currentTime"]);
    if (isFinite(t) && t > 0) return makeTimeRanges([[0, t]]);
    return makeTimeRanges([]);
  }

  ["fastSeek"](t: number): void {
    this["currentTime"] = t;
  }

  get ["currentTime"](): number {
    return __mseCurrentTime();
  }
  set ["currentTime"](t: number) {
    t = Number(t);
    if (!isFinite(t)) {
      throw new TypeError(
        "Failed to set the 'currentTime' property on 'HTMLMediaElement': The provided double value is non-finite.",
      );
    }
    /* HTML seeking: clamp to the media span, then nearest seekable range. */
    if (t < 0) t = 0;
    const d = this["duration"];
    if (isFinite(d) && t > d) t = d;
    const sk = this["seekable"];
    const n = sk ? sk["length"] : 0;
    if (n === 0) return;
    let inside = false;
    for (let i = 0; i < n; i++) {
      if (sk["start"](i) <= t && t <= sk["end"](i)) {
        inside = true;
        break;
      }
    }
    if (!inside) {
      const now = __mseCurrentTime();
      let best = sk["start"](0)!;
      let bestDist = Math.abs(t - best);
      for (let i = 0; i < n; i++) {
        const ends = [sk["start"](i), sk["end"](i)];
        for (let j = 0; j < 2; j++) {
          const p = ends[j]!;
          const dist = Math.abs(t - p);
          if (dist < bestDist || (dist === bestDist && Math.abs(p - now) < Math.abs(best - now))) {
            best = p;
            bestDist = dist;
          }
        }
      }
      t = best;
    }
    this["seeking"] = true;
    this["ended"] = false;
    const self = this;
    fire(self, "seeking");
    __mseSetCurrentTime(t);
    const t0 = Date.now();
    function waitSeeked(): void {
      const br = self["buffered"];
      let ok = false;
      const n = br ? br["length"] : 0;
      for (let i = 0; i < n; i++) {
        if (br["start"](i) <= t + 0.35 && br["end"](i) >= t - 0.05) ok = true;
      }
      if (ok || Date.now() - t0 > 8000) {
        self["seeking"] = false;
        fire(self, "seeked");
        return;
      }
      setTimeout(waitSeeked, 50);
    }
    Promise.resolve().then(waitSeeked);
  }
}

function attachHtmlMedia(el: object): void {
  initHtmlMedia(el as HTMLMediaElement);
  const proto = HTMLMediaElement.prototype as unknown as Record<string, unknown>;
  const methods = [
    "play",
    "pause",
    "load",
    "canPlayType",
    "addEventListener",
    "removeEventListener",
    "fastSeek",
  ];
  for (let i = 0; i < methods.length; i++) {
    const n = methods[i]!;
    const fn = proto[n];
    if (typeof fn === "function") (el as Record<string, unknown>)[n] = fn;
  }
  const props = ["src", "currentSrc", "currentTime", "duration", "buffered", "seekable", "played"];
  for (let i = 0; i < props.length; i++) {
    const n = props[i]!;
    const d = Object.getOwnPropertyDescriptor(proto, n);
    if (d) Object.defineProperty(el, n, d);
  }
}

export function mseCreateObjectURL(obj: MediaSource | { __id?: number } | null): string {
  const rec = obj as MediaSource;
  if (rec && rec.__id == null) rec.__id = nextId++;
  const url = "blob:mse/" + String(rec && rec.__id);
  if (rec) blobUrls[url] = rec;
  mseLog("mse createObjectURL " + url);
  return url;
}

export function mseRevokeObjectURL(url: string): void {
  delete blobUrls[String(url)];
}

const URLImpl = {
  "createObjectURL": mseCreateObjectURL,
  "revokeObjectURL": mseRevokeObjectURL,
};

/** Keep MediaSource + blob URLs on the global. IDL may replace URL; put createObjectURL back. */
export function installMseGlobals(): void {
  const glob = globalThis as unknown as Record<string, unknown>;
  glob["MediaSource"] = MediaSource;
  glob["SourceBuffer"] = SourceBuffer;
  glob["HTMLMediaElement"] = HTMLMediaElement;
  glob["HTMLVideoElement"] = HTMLMediaElement;
  glob["HTMLAudioElement"] = HTMLMediaElement;
  (HTMLMediaElement as unknown as Record<string, unknown>)["attach"] = attachHtmlMedia;
  const Url = glob["URL"];
  if (typeof Url === "function") {
    const rec = Url as unknown as Record<string, unknown>;
    rec["createObjectURL"] = mseCreateObjectURL;
    rec["revokeObjectURL"] = mseRevokeObjectURL;
  } else {
    glob["URL"] = URLImpl;
  }
}

export function createHtmlMedia(): HTMLMediaElement {
  const el = new HTMLMediaElement();
  activeMedia = el;
  return el;
}

export function activeHtmlMedia(): HTMLMediaElement | null {
  return activeMedia;
}

/** Drop the current MediaSource attachment and native decoder so the next video can start clean. */
export async function resetMse(): Promise<void> {
  const v = activeMedia;
  if (v) {
    v["paused"] = true;
    v._src = "";
    v._ms = null;
    v["readyState"] = 0;
    stopMediaTick();
    v["seeking"] = false;
    v["ended"] = false;
  }
  __mseReset();
}

installMseGlobals();
