// MSE — base.js talks to MediaSource / SourceBuffer / HTMLMediaElement as if this were Chrome.
// Append/play/seek hit C trampolines (`__mse*`). Quoted names so Closure ADVANCED leaves the
// DOM-shaped API intact for YouTube's player.

import { isAudioOnly } from "../logic/player/audio_only";
import { isYmLive } from "../logic/player/live";
import { viewportHeight, viewportWidth } from "../logic/player/viewport";

type Listener = (ev?: Record<string, unknown>) => void;

function mseLog(s: string): void {
  if (!MSE_DEBUG) return;
  console.log(s);
}

function audioFlag(mime: string): number {
  return String(mime).toLowerCase().indexOf("audio/") === 0 ? 1 : 0;
}

/** Never send non-audio to C while audio-only. */
function isDummyVideoMime(mime: string): boolean {
  if (audioFlag(mime) === 1) return false;
  return isAudioOnly();
}

/* Audio-only: server sends no video (SABR field 40=1) but base.js still locksteps
   on the video SourceBuffer. Report a leading buffered window locally — do not
   download, decode, or render. Video must lead or audio never appends.
   Cover to duration (else 120s) so ABR's video readahead check is satisfied. */
const DUMMY_READAHEAD_S = 120;
let dummySb: SourceBuffer | null = null;
let dummyCoverStart = 0;
let dummyCoverEnd = 0;

function audioBufferedEnd(): number {
  const r = __mseBuffered(1);
  let end = 0;
  if (r) {
    for (let i = 1; i < r.length; i += 2) {
      if (r[i]! > end) end = r[i]!;
    }
  }
  return end;
}

function videoHasLeftover(): boolean {
  const r = __mseBuffered(0);
  if (!r) return false;
  for (let i = 0; i + 1 < r.length; i += 2) {
    if (r[i + 1]! > r[i]!) return true;
  }
  return false;
}

function dummyDurationCap(ct: number): number {
  const el = activeMedia;
  if (el) {
    const d = Number(el["duration"]);
    if (isFinite(d) && d > 0) return d;
    const ms = el._ms;
    if (ms) {
      const md = Number(ms["duration"]);
      if (isFinite(md) && md > 0) return md;
    }
  }
  return ct + DUMMY_READAHEAD_S;
}

export function isLiveDuration(): boolean {
  if (isYmLive()) return true;
  const el = activeMedia;
  if (!el) return false;
  if (Number(el["duration"]) === Infinity) return true;
  const ms = el._ms;
  return !!(ms && Number(ms["duration"]) === Infinity);
}

function bindDummySb(): void {
  const ms = activeMedia && activeMedia._ms;
  if (!ms) return;
  const bufs = ms["sourceBuffers"];
  for (let i = 0; i < bufs.length; i++) {
    if (audioFlag(bufs[i]!["mimeType"]) === 0) {
      dummySb = bufs[i]!;
      return;
    }
  }
}

function coverDummyVideo(): void {
  if (!isAudioOnly()) {
    /* Keep the dummy window until C has real video, or media.buffered
       (intersection) goes empty and YouTube stalls. */
    if (videoHasLeftover()) {
      dummyCoverStart = 0;
      dummyCoverEnd = 0;
    }
    return;
  }
  if (!dummySb) bindDummySb();
  let ct = activeMedia ? __mseCurrentTime() : 0;
  const aEnd = audioBufferedEnd();
  /* Format-switch seek-to-0 (DVR start) must not collapse the dummy window. */
  if (ct < 1 && dummyCoverStart > 10) ct = dummyCoverStart + 1;
  /* Pin cover to C audio so a hole past leftover is not marked buffered. */
  const pastAudio = aEnd > 1 && ct > aEnd + 0.5;
  if (pastAudio) ct = aEnd;
  const start = Math.max(0, ct - 1);
  const head = Math.max(ct, aEnd);
  /* Audio-only dummy must be >0 (empty video HS/je → xpp skips drain) but
     not a 15s NRP lie: that made YouTube skip appendBuffer of SABR audio
     (log23 je=16s d=0/a=0; log10 je≈3s then audio appends). */
  const target = Math.max(start + 1, head + 2.5);
  if (dummyCoverStart <= start + 0.05 && dummyCoverEnd >= target - 0.05) return;
  const jump = Math.abs(start - dummyCoverStart) >= 1 || Math.abs(target - dummyCoverEnd) >= 1;
  dummyCoverStart = start;
  dummyCoverEnd = target;
  if (jump) mseLog("mse dummy SB cover " + dummyCoverStart + ".." + dummyCoverEnd);
  /* Do not pulse SourceBuffer.updating. base.js caches buffered by object
     reference (HS(!0)) and treats updating as "don't append / don't refresh".
     The getter is live, so sliding the numbers is enough. */
}

function asU8(data: ArrayBuffer | ArrayBufferView): Uint8Array {
  if (data instanceof Uint8Array) return data;
  if (ArrayBuffer.isView(data)) {
    return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
  }
  return new Uint8Array(data);
}

const drainCalls = { dummy: 0, audio: 0, video: 0, throw: 0, abort: 0, remove: 0, lisErr: 0 };

export function resetDrainCounts(): void {
  drainCalls.dummy = 0;
  drainCalls.audio = 0;
  drainCalls.video = 0;
  drainCalls.throw = 0;
  drainCalls.abort = 0;
  drainCalls.remove = 0;
  drainCalls.lisErr = 0;
}

let dummyStackOnce = "";

function shortStack(): string {
  if (dummyStackOnce) return dummyStackOnce;
  const s = new Error("d").stack || "";
  dummyStackOnce = s.replace(/\s+/g, " ").slice(0, 280);
  return dummyStackOnce;
}

function nativeRanges(audio: number): {
  length: number;
  start(i: number): number;
  end(i: number): number;
} | null {
  const r = __mseBuffered(audio);
  if (!r || r.length < 2) return null;
  return {
    "length": r.length / 2,
    "start": function (i: number) {
      return r[i * 2]!;
    },
    "end": function (i: number) {
      return r[i * 2 + 1]!;
    },
  };
}

function fmtBr(
  br: { length: number; start(i: number): number; end(i: number): number } | null,
): string {
  if (!br) return "";
  const n = br["length"];
  const parts: string[] = [];
  for (let i = 0; i < n; i++) {
    const a = br["start"](i);
    const b = br["end"](i);
    parts.push(Math.round(a * 1000) + "+" + Math.round((b - a) * 1000));
  }
  return parts.join(",");
}

/** YouTube `DI(buffered, t)`: leftover ms in the range that contains t, else 0. */
function leftoverMs(
  br: { length: number; start(i: number): number; end(i: number): number } | null,
  t: number,
): string {
  if (!br) return "-";
  const n = br["length"];
  for (let i = 0; i < n; i++) {
    const a = br["start"](i);
    const b = br["end"](i);
    if (t >= a - 0.05 && t <= b + 0.05) return String(Math.round((b - t) * 1000));
  }
  return "0";
}

function lisN(sb: { _listeners: Record<string, Listener[]> }, type: string): number {
  const list = sb._listeners[type];
  return list ? list.length : 0;
}

function fire(
  target: { _listeners: Record<string, Listener[]>; mimeType?: string },
  type: string,
): void {
  const list = target._listeners[type];
  if (!list) return;
  if (isAudioOnly() && type === "updateend") {
    const mime = String((target as { ["mimeType"]?: string })["mimeType"] || "");
    mseLog("mse evt updateend " + (audioFlag(mime) ? "a" : "v") + " lis=" + list.length);
  }
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
    } catch (e) {
      drainCalls.lisErr++;
      const err = e instanceof Error ? e : new Error(String(e));
      const st = err.stack ? " " + err.stack.replace(/\s+/g, " ").slice(0, 240) : "";
      mseLog("mse listener " + type + " err=" + err.name + " " + err.message + st);
    }
  }
}

/* Chrome: append/remove while updating is InvalidStateError (code 11). TypeError
   is not a DOMException; YouTube's TZP then returns 4 → reattachOnAppend and
   never drains the SABR append queue. */
function domException(name: string, msg: string, code: number): Error {
  const Ctor = (globalThis as unknown as { DOMException?: new (m: string, n: string) => Error })
    .DOMException;
  if (typeof Ctor === "function") {
    try {
      return new Ctor(msg, name);
    } catch (_e) {}
  }
  const err = new Error(msg);
  err.name = name;
  (err as unknown as { code: number }).code = code;
  return err;
}

function invalidState(msg: string): Error {
  return domException("InvalidStateError", msg, 11);
}

function notSupported(msg: string): Error {
  return domException("NotSupportedError", msg, 9);
}

function completeUpdate(sb: SourceBuffer): void {
  Promise.resolve().then(function () {
    sb["updating"] = false;
    fire(sb, "update");
    fire(sb, "updateend");
  });
}

let nextId = 1;

class SourceBuffer {
  ["mimeType"]: string;
  ["id"]: number;
  ["updating"]: boolean;
  ["timestampOffset"]: number;
  ["appendWindowStart"]: number;
  ["appendWindowEnd"]: number;
  _ms: MediaSource | null;
  _listeners: Record<string, Listener[]>;
  /* One TimeRanges object for the life of the SB. base.js HS(!0) caches
     this by reference; start/end must read current leftover/dummy. */
  _liveBuffered: { length: number; start(i: number): number; end(i: number): number } | null;

  constructor(mime: string, ms: MediaSource | null) {
    this["mimeType"] = mime;
    this["id"] = nextId++;
    this["updating"] = false;
    this["timestampOffset"] = 0;
    this["appendWindowStart"] = 0;
    this["appendWindowEnd"] = Infinity;
    this._ms = ms;
    this._listeners = {};
    this._liveBuffered = null;
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
    const self = this;
    if (!self._liveBuffered) {
      self._liveBuffered = liveTimeRanges(function () {
        return self._bufferedPairs();
      });
    }
    return self._liveBuffered;
  }

  _bufferedPairs(): Array<[number, number]> {
    /* Audio-only: short dummy so video je>0 (xpp still drains) but SABR
       still appends audio. Leave-audio: a sliver so video readahead fails
       and googlevideo sends video now. */
    if (
      audioFlag(this["mimeType"]) === 0 &&
      dummyCoverEnd > dummyCoverStart &&
      (isAudioOnly() || !videoHasLeftover())
    ) {
      if (isAudioOnly()) return [[dummyCoverStart, dummyCoverEnd]];
      const ct = __mseCurrentTime();
      const start = Math.max(0, ct - 0.05);
      return [[start, start + 0.35]];
    }
    const r = __mseBuffered(audioFlag(this["mimeType"]));
    const ranges: Array<[number, number]> = [];
    if (r) {
      for (let i = 0; i + 1 < r.length; i += 2) ranges.push([r[i]!, r[i + 1]!]);
    }
    return ranges;
  }

  ["changeType"](mime: string): void {
    /* Chrome SourceBuffer::ChangeType_Locked (WICG / MSE codec switching):
       empty type → TypeError; removed or updating → InvalidStateError;
       unsupported type → NotSupportedError; ended parent → open+sourceopen;
       reset parser state; pending initialization segment = true. */
    const next = String(mime || "");
    if (!next) throw new TypeError("The type provided is empty");
    const parent = this._ms;
    if (!parent || parent["sourceBuffers"].indexOf(this) < 0)
      throw invalidState("SourceBuffer.changeType: removed");
    if (this["updating"]) throw invalidState("SourceBuffer.changeType: updating");
    if (!MediaSource["isTypeSupported"](next))
      throw notSupported("Changing to the type provided ('" + next + "') is not supported.");
    if (parent["readyState"] === "ended") {
      parent["readyState"] = "open";
      fire(parent, "sourceopen");
      fire(parent, "webkitsourceopen");
    }
    const prev = String(this["mimeType"] || "");
    const wasDummy = isDummyVideoMime(prev);
    const nowDummy = isDummyVideoMime(next);
    const audio = audioFlag(next);
    try {
      const ct = __mseCurrentTime();
      const br = nativeRanges(audio);
      mseLog(
        "mse changeType a=" +
          audio +
          " from=" +
          prev +
          " to=" +
          next +
          " ct=" +
          ct.toFixed(3) +
          " leftover=" +
          leftoverMs(br, ct) +
          " br=[" +
          fmtBr(br) +
          "]",
      );
    } catch (_e) {
      mseLog("mse changeType a=" + audio + " from=" + prev + " to=" + next);
    }
    this["mimeType"] = next;
    if (!wasDummy && nowDummy) {
      dummySb = this;
      coverDummyVideo();
      __mseRemove(0, 0, 1e300);
      __mseAbort(0);
    } else if (wasDummy && !nowDummy) {
      if (dummySb === this) dummySb = null;
      dummyCoverStart = 0;
      dummyCoverEnd = 0;
      __mseChangeType(audio);
    } else if (!nowDummy) {
      __mseChangeType(audio);
    }
  }

  ["appendBuffer"](data: ArrayBuffer | ArrayBufferView): void {
    const dummy = isDummyVideoMime(this["mimeType"]);
    const audio = audioFlag(this["mimeType"]);
    let nbytes = -1;
    try {
      nbytes = asU8(data).length;
    } catch (_e) {}
    if (this["updating"]) {
      drainCalls.throw++;
      mseLog(
        "mse append throw updating dummy=" +
          (dummy ? "1" : "0") +
          " a=" +
          audio +
          " bytes=" +
          nbytes,
      );
      throw invalidState("SourceBuffer.appendBuffer: updating");
    }
    this["updating"] = true;
    fire(this, "updatestart");
    const self = this;
    try {
      if (dummy) {
        drainCalls.dummy++;
        dummySb = self;
        coverDummyVideo();
        mseLog(
          "mse append dummy v bytes=" +
            nbytes +
            " mime=" +
            self["mimeType"] +
            " ue=" +
            lisN(self, "updateend") +
            " " +
            shortStack(),
        );
      } else {
        const u8 = asU8(data);
        const tsOff = self["timestampOffset"];
        const winStart = self["appendWindowStart"];
        const winEnd = self["appendWindowEnd"] === Infinity ? 1e300 : self["appendWindowEnd"];
        __mseAppend(audio, u8, tsOff, winStart, winEnd);
        /* Live join: first media is at DVR head (e.g. 7406s) while
           currentTime is still 0. C starve-ahead until YouTube seeks
           (log6: 20k starve lines). Snap clock to that island. */
        if (__mseCurrentTime() < 1) {
          const br = __mseBuffered(audio);
          if (br && br.length >= 2 && br[0]! > 60) {
            mseLog("mse live-join clock " + br[0]);
            __mseSetCurrentTime(br[0]!);
          }
        }
        if (audio) {
          drainCalls.audio++;
          coverDummyVideo();
        } else {
          drainCalls.video++;
        }
        try {
          const ct = __mseCurrentTime();
          const ranges = nativeRanges(audio);
          mseLog(
            "mse append a=" +
              audio +
              " bytes=" +
              u8.length +
              " mime=" +
              self["mimeType"] +
              " tsOff=" +
              tsOff +
              " win=" +
              winStart +
              ".." +
              winEnd +
              " leftover=" +
              leftoverMs(ranges, ct) +
              " br=[" +
              fmtBr(ranges) +
              "]",
          );
        } catch (_e) {
          mseLog("mse append a=" + audio + " bytes=" + u8.length);
        }
      }
    } catch (e) {
      drainCalls.throw++;
      self["updating"] = false;
      const err = e instanceof Error ? e : new Error(String(e));
      mseLog(
        "mse append throw " +
          err.name +
          " dummy=" +
          (dummy ? "1" : "0") +
          " a=" +
          audio +
          " " +
          err.message,
      );
      throw e;
    }
    completeUpdate(self);
  }

  ["remove"](start: number, end: number): void {
    if (this["updating"]) throw invalidState("SourceBuffer.remove: updating");
    this["updating"] = true;
    fire(this, "updatestart");
    const self = this;
    try {
      const audio = audioFlag(this["mimeType"]);
      const dummy = isDummyVideoMime(this["mimeType"]);
      if (!dummy) {
        __mseRemove(audio, start, end);
      }
      drainCalls.remove++;
      mseLog("mse remove dummy=" + (dummy ? "1" : "0") + " a=" + audio + " " + start + ".." + end);
    } catch (e) {
      self["updating"] = false;
      throw e;
    }
    Promise.resolve().then(function () {
      if (isDummyVideoMime(self["mimeType"]) && isAudioOnly()) coverDummyVideo();
      self["updating"] = false;
      fire(self, "update");
      fire(self, "updateend");
    });
  }

  ["abort"](): void {
    const dummy = isDummyVideoMime(this["mimeType"]);
    const audio = audioFlag(this["mimeType"]);
    const wasUpdating = this["updating"];
    drainCalls.abort++;
    mseLog(
      "mse abort dummy=" +
        (dummy ? "1" : "0") +
        " a=" +
        audio +
        " upd=" +
        (wasUpdating ? "1" : "0"),
    );
    if (dummy) {
      this["updating"] = false;
      /* MSE: abort/updateend only if an update was in progress. Firing updateend while
       * already in updateend (updating=false) re-enters YouTube's listener until the stack blows. */
      if (wasUpdating) {
        fire(this, "abort");
        fire(this, "updateend");
      }
      if (isAudioOnly()) coverDummyVideo();
      return;
    }
    __mseAbort(audio);
    this["updating"] = false;
    if (wasUpdating) {
      fire(this, "abort");
      fire(this, "updateend");
    }
  }
}

function liveTimeRanges(read: () => Array<[number, number]>): {
  length: number;
  start(i: number): number;
  end(i: number): number;
} {
  return {
    get ["length"]() {
      return read().length;
    },
    "start": function (i: number) {
      return read()[i]![0];
    },
    "end": function (i: number) {
      return read()[i]![1];
    },
  };
}

function makeTimeRanges(ranges: Array<[number, number]>): {
  length: number;
  start(i: number): number;
  end(i: number): number;
} {
  const snap = (ranges || []).slice();
  return liveTimeRanges(function () {
    return snap;
  });
}

function sbRanges(sb: SourceBuffer): Array<[number, number]> {
  const br = sb["buffered"];
  const out: Array<[number, number]> = [];
  for (let i = 0; i < br["length"]; i++) out.push([br["start"](i), br["end"](i)]);
  return out;
}

function intersectRanges(
  a: Array<[number, number]>,
  b: Array<[number, number]>,
): Array<[number, number]> {
  const out: Array<[number, number]> = [];
  for (let i = 0; i < a.length; i++) {
    for (let j = 0; j < b.length; j++) {
      const lo = Math.max(a[i]![0], b[j]![0]);
      const hi = Math.min(a[i]![1], b[j]![1]);
      if (hi > lo) out.push([lo, hi]);
    }
  }
  return out;
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
    /* Infinity = live. A leftover finite head must not keep clamping
       currentTime (log23: clock stuck at 17253 after sourceopen). */
    if (v === Infinity || !isFinite(v) || v <= 0) __mseSetDuration(0);
    else __mseSetDuration(v);
    coverDummyVideo();
  }

  static ["isTypeSupported"](mime: string): boolean {
    const m = String(mime).toLowerCase();
    return (
      m.indexOf("fakesb") === 0 ||
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
    if (isDummyVideoMime(mime)) mseLog("mse dummy SB " + mime);
    const sb = new SourceBuffer(mime, this);
    this["sourceBuffers"].push(sb);
    if (isDummyVideoMime(mime)) {
      dummySb = sb;
      /* Keep the live dummy window across YouTube's vp9→avc1 SourceBuffer swap. */
      if (!isAudioOnly() || dummyCoverEnd <= dummyCoverStart) {
        dummyCoverStart = 0;
        dummyCoverEnd = 0;
      }
      coverDummyVideo();
    }
    return sb;
  }

  ["removeSourceBuffer"](sb: SourceBuffer): void {
    const i = this["sourceBuffers"].indexOf(sb);
    if (i < 0) throw invalidState("MediaSource.removeSourceBuffer: not in sourceBuffers");
    if (sb["updating"]) sb["abort"]();
    this["sourceBuffers"].splice(i, 1);
    sb._ms = null;
    if (dummySb === sb) dummySb = null;
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

function detachMediaSource(ms: MediaSource): void {
  const bufs = ms["sourceBuffers"].slice();
  for (let i = 0; i < bufs.length; i++) {
    bufs[i]!["updating"] = false;
    bufs[i]!._ms = null;
  }
  ms["sourceBuffers"].length = 0;
  ms._duration = NaN;
  if (ms["readyState"] !== "closed") {
    ms["readyState"] = "closed";
    fire(ms, "sourceclose");
    fire(ms, "webkitsourceclose");
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
    coverDummyVideo();
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

  get ["videoWidth"](): number {
    return viewportWidth();
  }
  get ["videoHeight"](): number {
    return viewportHeight();
  }

  ["canPlayType"](t: string): string {
    t = String(t || "").toLowerCase();
    if (t.indexOf("fakesb") === 0) return "probably";
    return t.indexOf("webm") >= 0 || t.indexOf("mp4") >= 0 ? "probably" : "";
  }

  ["load"](): void {
    /* HTML media element load algorithm: stop, forget the resource, then
       resource-select src again. MSE detach closes the MediaSource. */
    const url = this._src;
    this["paused"] = true;
    this["ended"] = false;
    this["seeking"] = false;
    stopMediaTick();
    __msePause();
    if (this._ms) detachMediaSource(this._ms);
    dummySb = null;
    dummyCoverStart = 0;
    dummyCoverEnd = 0;
    __mseReset();
    this._ms = null;
    this._src = "";
    this["readyState"] = 0;
    if (url) this["src"] = url;
  }

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
    coverDummyVideo();
    Promise.resolve().then(function () {
      fire(self, "pause");
    });
  }

  get ["src"](): string {
    return this._src;
  }
  set ["src"](url: string) {
    const prev = this._ms;
    const ms = blobUrls[url] || null;
    this._src = url;
    mseLog("mse video.src " + url + (ms ? " mse" : " not-mse"));
    if (prev !== ms) {
      /* Chrome: assigning a new media resource detaches the old MediaSource
         (readyState closed, sourceBuffers removed) and the pipeline demuxer
         is a new empty ChunkDemuxer. */
      if (prev) detachMediaSource(prev);
      dummySb = null;
      dummyCoverStart = 0;
      dummyCoverEnd = 0;
      __mseReset();
      this._ms = ms;
      if (ms && ms["readyState"] === "closed") {
        ms["readyState"] = "open";
        fire(ms, "sourceopen");
        fire(ms, "webkitsourceopen");
        mseLog("mse sourceopen");
      }
    } else {
      this._ms = ms;
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
    else if (v === Infinity || !isFinite(v) || v <= 0) __mseSetDuration(0);
    else __mseSetDuration(v);
  }

  get ["buffered"](): { length: number; start(i: number): number; end(i: number): number } {
    /* Spec is intersection of SourceBuffers. Audio leftover ends at the
       playhead, so je()=DI(buffered, currentTime) is ~0, YouTube sets
       buffering (Ys) and BZY skips SABR already in the append queue.
       Long dummy keeps je() large until C has real video leftover — including
       after leave-audio, when the video SB getter reports only a sliver. */
    if (dummyCoverEnd > dummyCoverStart && (isAudioOnly() || !videoHasLeftover())) {
      return makeTimeRanges([[dummyCoverStart, dummyCoverEnd]]);
    }
    const ms = this._ms;
    if (!ms) return makeTimeRanges([]);
    const bufs = ms["sourceBuffers"];
    if (!bufs.length) return makeTimeRanges([]);
    let acc = sbRanges(bufs[0]!);
    for (let i = 1; i < bufs.length; i++) acc = intersectRanges(acc, sbRanges(bufs[i]!));
    return makeTimeRanges(acc);
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
    /* currentTime must be a finite media time. Infinity / huge sentinels
       clamp to the last buffered end (or 0 if empty) — not duration. */
    if (t === Infinity || t > 1e11) {
      const b = this["buffered"];
      const n = b ? b["length"] : 0;
      t = n > 0 ? b["end"](n - 1) : 0;
    } else if (!isFinite(t)) {
      return;
    }
    /* Clamp to a finite duration. Do not snap to leftover — DVR -10s is often
       just outside buffered; SABR fills from the new playhead. */
    if (t < 0) t = 0;
    const d = this["duration"];
    if (isFinite(d) && t > d) t = d;
    const prev = __mseCurrentTime();
    /* Live format-switch seeks 0 (DVR start). Ignore while dummy still
       owns video — including leave-audio, after __ymAudioOnly is already
       false. Feeding that seek into C starts VP9/AV1 on a P-frame (SIGILL). */
    if (t < 1 && prev > 10 && (isAudioOnly() || !videoHasLeftover())) {
      mseLog("mse ignore seek-0 audio-only from " + prev);
      return;
    }
    const aEnd = audioBufferedEnd();
    /* Live head only. VOD hole-seek must keep currentTime at t so SABR
       fetches that playhead (log3: clamp 166→110 left the clock behind
       YouTube's playerTimeMs). */
    if (isAudioOnly() && isLiveDuration() && aEnd > 1 && t > aEnd + 0.75) {
      mseLog("mse clamp seek " + t + " to audioEnd " + aEnd);
      t = aEnd;
    }
    this["seeking"] = true;
    this["ended"] = false;
    const self = this;
    fire(self, "seeking");
    __mseSetCurrentTime(t);
    coverDummyVideo();
    const t0 = Date.now();
    function finishSeek(): void {
      self["seeking"] = false;
      fire(self, "seeked");
    }
    function waitSeeked(): void {
      /* Dummy TimeRanges are a lockstep lie. Audio-only seeked waits on
         C audio leftover covering t, or SABR never has to fill the hole. */
      if (isAudioOnly()) {
        const aEnd = audioBufferedEnd();
        if (aEnd >= t - 0.35 || Date.now() - t0 > 8000) {
          finishSeek();
          return;
        }
        /* log14: load 2nd live while audio-only. YouTube seeks DVR head
           before C has audio. Holding seeking blocks that append. */
        if (t > 60 && aEnd < 1) {
          mseLog("mse seeked live-empty t=" + t);
          finishSeek();
          return;
        }
        setTimeout(waitSeeked, 50);
        return;
      }
      const br = self["buffered"];
      let ok = false;
      const n = br ? br["length"] : 0;
      for (let i = 0; i < n; i++) {
        if (br["start"](i) <= t + 0.35 && br["end"](i) >= t - 0.05) ok = true;
      }
      if (ok || n === 0 || Date.now() - t0 > 8000) {
        finishSeek();
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
  const props = [
    "src",
    "currentSrc",
    "currentTime",
    "duration",
    "buffered",
    "seekable",
    "played",
    "videoWidth",
    "videoHeight",
  ];
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

/** MSE-only drain line. Do not walk base.js internals. */
export function dumpDrain(why: string): void {
  if (!MSE_DEBUG) return;
  if (!isAudioOnly() && why.indexOf("setQuality") < 0) return;
  try {
    mseLog("mse drain " + why + " " + dumpMseDrain());
  } catch (e) {
    const err = e instanceof Error ? e : new Error(String(e));
    mseLog("mse drain " + why + " err=" + err.message);
  }
}

/** Protocol drain snapshot: dummy vs audio append, updating, je, listeners. */
export function dumpMseDrain(): string {
  const el = activeMedia;
  const ct = el ? __mseCurrentTime() : 0;
  const dummy =
    dummyCoverEnd > dummyCoverStart
      ? dummyCoverStart.toFixed(2) + ".." + dummyCoverEnd.toFixed(2)
      : "-";
  let mediaBuf = "";
  let mediaJe = "-";
  let paused = "-";
  let rs = "-";
  let seeking = "-";
  if (el) {
    try {
      const b = el["buffered"];
      mediaBuf = fmtBr(b);
      mediaJe = leftoverMs(b, ct);
    } catch (_e) {
      mediaBuf = "err";
    }
    paused = el["paused"] ? "1" : "0";
    rs = String(el["readyState"]);
    seeking = el["seeking"] ? "1" : "0";
  }
  const parts: string[] = [
    "ct=" + ct.toFixed(3),
    "paused=" + paused,
    "rs=" + rs,
    "seek=" + seeking,
    "dummy=" + dummy,
    "media.buf=[" + mediaBuf + "]",
    "media.je=" + mediaJe,
  ];
  const ms = el && el._ms;
  const bufs = ms ? ms["sourceBuffers"] : [];
  for (let i = 0; i < bufs.length; i++) {
    const sb = bufs[i]!;
    const audio = audioFlag(sb["mimeType"]);
    const isDummy = isDummyVideoMime(sb["mimeType"]);
    let buf = "";
    let je = "-";
    try {
      const b = sb["buffered"];
      buf = fmtBr(b);
      je = leftoverMs(b, ct);
    } catch (_e) {
      buf = "err";
    }
    parts.push(
      (audio ? "a" : isDummy ? "dv" : "v") +
        "{upd=" +
        (sb["updating"] ? "1" : "0") +
        " mime=" +
        sb["mimeType"] +
        " buf=[" +
        buf +
        "] je=" +
        je +
        " ue=" +
        lisN(sb, "updateend") +
        "}",
    );
  }
  parts.push(
    "calls d=" +
      drainCalls.dummy +
      "/a=" +
      drainCalls.audio +
      "/v=" +
      drainCalls.video +
      " throw=" +
      drainCalls.throw +
      " abort=" +
      drainCalls.abort +
      " rm=" +
      drainCalls.remove +
      " lisErr=" +
      drainCalls.lisErr,
  );
  return parts.join(" ");
}

/** Drop decoded video leftover. Audio-only recaps the dummy window. Leaving
 *  audio-only keeps that window until C has real video leftover. */
export function dropVideoTrack(): void {
  if (isAudioOnly()) {
    /* Dummy window first so video.buffered never goes empty — YouTube's
       append drain (l7/BZY) skips SABR audio if video HS/je is 0. */
    bindDummySb();
    coverDummyVideo();
    __mseRemove(0, 0, 1e300);
    __mseAbort(0);
    if (dummySb) {
      if (dummySb["updating"]) dummySb["abort"]();
      else {
        /* abort() with updating=false must not emit updateend (re-enters
           YouTube's listener until the stack blows). The SABR queue still
           needs that event to flush into appendBuffer (log10: abort upd=0
           then updateend then audio appends; log23 today: no updateend,
           d=0/a=0, clock frozen at leftover end). */
        fire(dummySb, "updateend");
      }
    }
    return;
  }
  __mseRemove(0, 0, 1e300);
  __mseAbort(0);
  /* Leaving audio-only: keep dummy cover until C has video leftover so
     media.buffered still contains the playhead (intersection). Aborting
     here emptied video.buffered and stalled. */
}

/** Drop the current MediaSource attachment and native decoder so the next video can start clean. */
export async function resetMse(): Promise<void> {
  const v = activeMedia;
  if (v) {
    v["paused"] = true;
    if (v._ms) detachMediaSource(v._ms);
    v._src = "";
    v._ms = null;
    v["readyState"] = 0;
    stopMediaTick();
    v["seeking"] = false;
    v["ended"] = false;
  }
  dummySb = null;
  dummyCoverStart = 0;
  dummyCoverEnd = 0;
  __mseReset();
}

installMseGlobals();
