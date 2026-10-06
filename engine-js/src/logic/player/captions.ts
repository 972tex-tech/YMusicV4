// YouTube timedtext (json3) → 2-line paint-on window. Host opens player/caption to subscribe.

import { debug } from "../../debug";
import { JsEngineErrorImpl } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA } from "../yt/session";
import { activeHtmlMedia, onActiveHtmlMedia } from "../../protocol/mse";

type CaptionTrack = {
  vssId: string;
  languageCode: string;
  kind: string;
  displayName: string;
  baseUrl: string;
  tlang?: string;
};

type Word = { tOffsetMs: number; text: string };
type Cue = { id: number; startMs: number; durationMs: number; words: Word[] };
type Line = { id: number; text: string };

let tracks: CaptionTrack[] = [];
let videoId = "";
let selectedVss: string | null = null;
/* The viewer's pick outlives the video: each ingest re-selects from it, like youtube.com. */
let captionOn = false;
let preferVss: string | null = null;
let preferLanguage: string | null = null;
let cues: Cue[] = [];
let mintPo: ((id: string) => Promise<string>) | null = null;
let sessionSink: PipeSink | null = null;
let sessionPipe: Pipe | null = null;
let tickTimer: ReturnType<typeof setTimeout> | null = null;
let fetchGen = 0;
let lastLinesJson = "";
let ingestTick = 0;
let ingestWait: Array<() => void> = [];

function noteIngest(): void {
  ingestTick++;
  const w = ingestWait;
  ingestWait = [];
  for (let i = 0; i < w.length; i++) w[i]!();
}

export function captionIngestTick(): number {
  return ingestTick;
}

/** Resolves on the next /player caption ingest after [prev], or when [ms] elapses. */
export function waitCaptionIngest(prev: number, ms: number): Promise<void> {
  if (ingestTick !== prev) return Promise.resolve();
  return new Promise(function (resolve) {
    const t = setTimeout(function () {
      const i = ingestWait.indexOf(done);
      if (i >= 0) ingestWait.splice(i, 1);
      resolve();
    }, ms);
    function done(): void {
      clearTimeout(t);
      resolve();
    }
    ingestWait.push(done);
  });
}

function asRec(v: unknown): Record<string, unknown> | null {
  if (v == null || typeof v !== "object") return null;
  return v as Record<string, unknown>;
}

function simpleName(v: unknown): string {
  if (typeof v === "string") return v;
  const o = asRec(v);
  if (o && typeof o["simpleText"] === "string") return o["simpleText"] as string;
  return "";
}

export function installCaptionMinter(fn: (id: string) => Promise<string>): void {
  mintPo = fn;
}

export function ingestPlayerCaptions(rec: Record<string, unknown>): void {
  const caps = asRec(rec["captions"]);
  const list = caps ? asRec(caps["playerCaptionsTracklistRenderer"]) : null;
  const raw = list ? list["captionTracks"] : null;
  const vid = asRec(rec["videoDetails"]);
  videoId = vid && typeof vid["videoId"] === "string" ? (vid["videoId"] as string) : videoId;
  const out: CaptionTrack[] = [];
  if (Array.isArray(raw)) {
    for (let i = 0; i < raw.length; i++) {
      const t = asRec(raw[i]);
      if (!t) continue;
      const url = typeof t["baseUrl"] === "string" ? t["baseUrl"] : "";
      const vss = typeof t["vssId"] === "string" ? t["vssId"] : "";
      const lang = typeof t["languageCode"] === "string" ? t["languageCode"] : "";
      if (!url || !vss) continue;
      const kind = typeof t["kind"] === "string" ? t["kind"] : "";
      const name = simpleName(t["name"]) || lang;
      out.push({
        vssId: vss,
        languageCode: lang,
        kind: kind,
        displayName: kind === "asr" ? name + " (auto)" : name,
        baseUrl: url,
      });
    }
  }
  const trans = list ? list["translationLanguages"] : null;
  let src: CaptionTrack | null = null;
  for (let i = 0; i < out.length; i++) {
    if (out[i]!.kind !== "asr") {
      src = out[i]!;
      break;
    }
  }
  if (!src && out.length) src = out[0]!;
  if (src && Array.isArray(trans)) {
    const seen: Record<string, boolean> = {};
    for (let i = 0; i < out.length; i++) seen[out[i]!.languageCode] = true;
    for (let i = 0; i < trans.length; i++) {
      const tr = asRec(trans[i]);
      if (!tr) continue;
      const code = typeof tr["languageCode"] === "string" ? tr["languageCode"] : "";
      if (!code || seen[code]) continue;
      seen[code] = true;
      const nm = simpleName(tr["languageName"]) || code;
      out.push({
        vssId: src.vssId + ">" + code,
        languageCode: code,
        kind: "asrt",
        displayName: nm + " (auto translate)",
        baseUrl: src.baseUrl,
        tlang: code,
      });
    }
  }
  tracks = out;
  selectedVss = captionOn ? pickTrack() : null;
  cues = [];
  debug(function () {
    return "caption ingest tracks=" + out.length + " vid=" + videoId;
  });
  noteIngest();
  if (sessionSink) void reloadCues();
  if (captionsChanged) captionsChanged();
}

let captionsChanged: (() => void) | null = null;

export function onCaptionsChanged(fn: (() => void) | null): void {
  captionsChanged = fn;
}

export function listCaptionTracks(): Record<string, unknown> {
  return { "tracks": captionTrackRows() };
}

export function captionTrackRows(): Record<string, unknown>[] {
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < tracks.length; i++) {
    const t = tracks[i]!;
    rows.push({
      "vssId": t.vssId,
      "languageCode": t.languageCode,
      "kind": t.kind,
      "displayName": t.displayName,
    });
  }
  return rows;
}

export function selectedCaptionVss(): string | null {
  return selectedVss;
}

/** [key] is a vssId or a language code; it becomes the preference kept across videos. */
export function setCaptionPreference(visible: boolean, key: string | null): void {
  captionOn = visible;
  if (key) {
    const track = findTrack(key);
    preferVss = track ? track.vssId : null;
    preferLanguage = track ? track.languageCode : key;
  }
  selectedVss = captionOn ? pickTrack() : null;
  cues = [];
  if (sessionSink) void reloadCues();
  if (captionsChanged) captionsChanged();
}

/** Exact pick, then the same language (human before auto), then auto-generated, then any. */
function pickTrack(): string | null {
  if (preferVss && findTrack(preferVss)) return preferVss;
  let sameLanguageAuto: CaptionTrack | null = null;
  let firstAuto: CaptionTrack | null = null;
  let firstHuman: CaptionTrack | null = null;
  for (let i = 0; i < tracks.length; i++) {
    const track = tracks[i]!;
    if (track.languageCode === preferLanguage) {
      if (track.kind !== "asr" && track.kind !== "asrt") return track.vssId;
      if (!sameLanguageAuto || sameLanguageAuto.kind === "asrt") sameLanguageAuto = track;
    }
    if (track.kind === "asr" && !firstAuto) firstAuto = track;
    if (track.kind !== "asr" && track.kind !== "asrt" && !firstHuman) firstHuman = track;
  }
  const picked = sameLanguageAuto || firstAuto || firstHuman;
  return picked ? picked.vssId : null;
}

function findTrack(vss: string): CaptionTrack | null {
  for (let i = 0; i < tracks.length; i++) {
    if (tracks[i]!.vssId === vss) return tracks[i]!;
  }
  return null;
}

function hasQuery(url: string, key: string): boolean {
  const k = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp("(?:^|[?&])" + k + "=").test(url);
}

function withFmtJson3(url: string): string {
  return setQuery(url, "fmt", "json3");
}

function setQuery(url: string, key: string, val: string): string {
  const k = key.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const enc = encodeURIComponent(val);
  if (hasQuery(url, key))
    return url.replace(new RegExp("([?&])" + k + "=[^&]*"), "$1" + key + "=" + enc);
  return url + (url.indexOf("?") >= 0 ? "&" : "?") + key + "=" + enc;
}

function withQuery(url: string, key: string, val: string): string {
  if (hasQuery(url, key)) return url;
  return setQuery(url, key, val);
}

function ytcfgStr(key: string): string {
  const g = globalThis as unknown as Record<string, unknown>;
  const cfg = asRec(g["ytcfg"]);
  const get = cfg ? cfg["get"] : null;
  if (typeof get === "function") {
    const v = (get as (k: string) => unknown)(key);
    return typeof v === "string" ? v : "";
  }
  const data = cfg ? asRec(cfg["data_"]) : null;
  const v = data ? data[key] : null;
  return typeof v === "string" ? v : "";
}

/** Chrome json3 timedtext: fmt + xorb/xobt/xovt/xosf + potc/c/cver. Skip any of these and YouTube returns HTTP 200 empty text/html. */
function withCaptionClient(url: string): string {
  url = setQuery(url, "potc", "1");
  url = setQuery(url, "c", "WEB");
  url = setQuery(url, "cplayer", "UNIPLAYER");
  url = setQuery(url, "xorb", "2");
  url = setQuery(url, "xobt", "3");
  url = setQuery(url, "xovt", "3");
  url = setQuery(url, "xosf", "1");
  const cver = ytcfgStr("INNERTUBE_CLIENT_VERSION");
  if (cver) url = setQuery(url, "cver", cver);
  return url;
}

function queryFlags(url: string): string {
  return (
    "fmt=" +
    (hasQuery(url, "fmt") ? "1" : "0") +
    " pot=" +
    (hasQuery(url, "pot") ? "1" : "0") +
    " potc=" +
    (hasQuery(url, "potc") ? "1" : "0") +
    " n=" +
    (hasQuery(url, "n") ? "1" : "0") +
    " s=" +
    (hasQuery(url, "s") ? "1" : "0") +
    " sig=" +
    (hasQuery(url, "signature") ? "1" : "0") +
    " c=" +
    (hasQuery(url, "c") ? "1" : "0") +
    " xorb=" +
    (hasQuery(url, "xorb") ? "1" : "0") +
    " xosf=" +
    (hasQuery(url, "xosf") ? "1" : "0")
  );
}

function stripXssi(s: string): string {
  let i = 0;
  while (i < s.length && s.charCodeAt(i) <= 32) i++;
  if (s.charCodeAt(i) === 0xfeff) i++;
  if (s.charAt(i) === ")") {
    const nl = s.indexOf("\n", i);
    if (nl >= 0) return s.slice(nl + 1);
    const b = s.indexOf("{", i);
    if (b >= 0) return s.slice(b);
  }
  return i ? s.slice(i) : s;
}

function parseJson3(raw: string): Cue[] {
  let rec: Record<string, unknown>;
  try {
    rec = JSON.parse(stripXssi(raw)) as Record<string, unknown>;
  } catch (_e) {
    console.warn("caption timedtext not json head=" + raw.slice(0, 80).replace(/\n/g, " "));
    return [];
  }
  const events = rec["events"];
  if (!Array.isArray(events)) return [];
  const out: Cue[] = [];
  for (let i = 0; i < events.length; i++) {
    const ev = asRec(events[i]);
    if (!ev) continue;
    const segs = ev["segs"];
    if (!Array.isArray(segs) || segs.length === 0) continue;
    const start = Number(ev["tStartMs"]);
    const dur = Number(ev["dDurationMs"]);
    if (!isFinite(start)) continue;
    const words: Word[] = [];
    let full = "";
    for (let j = 0; j < segs.length; j++) {
      const s = asRec(segs[j]);
      if (!s) continue;
      const utf = typeof s["utf8"] === "string" ? (s["utf8"] as string) : "";
      const off = s["tOffsetMs"] != null ? Number(s["tOffsetMs"]) : 0;
      const tOff = isFinite(off) ? off : 0;
      words.push({ tOffsetMs: tOff, text: utf });
      full += utf;
    }
    if (!full.trim().length) continue;
    out.push({
      id: i,
      startMs: start,
      durationMs: isFinite(dur) && dur > 0 ? dur : 4000,
      words: words,
    });
  }
  return out;
}

function prefixOf(c: Cue, nowMs: number): string {
  const rel = nowMs - c.startMs;
  let s = "";
  for (let i = 0; i < c.words.length; i++) {
    if (c.words[i]!.tOffsetMs <= rel) s += c.words[i]!.text;
  }
  return s;
}

function windowAt(nowMs: number): Line[] {
  if (!cues.length) return [];
  let cur = -1;
  for (let i = 0; i < cues.length; i++) {
    if (cues[i]!.startMs <= nowMs) cur = i;
    else break;
  }
  if (cur < 0) return [];
  const c = cues[cur]!;
  const ended = nowMs >= c.startMs + c.durationMs;
  const next = cur + 1 < cues.length ? cues[cur + 1] : null;
  if (ended && (!next || next.startMs > nowMs)) {
    /* A short gap before the next cue holds this one, so the box rolls up rather than blanking. */
    if (!next || next.startMs - (c.startMs + c.durationMs) > ROLL_UP_GAP_MS) return [];
  }
  const track = selectedVss ? findTrack(selectedVss) : null;
  const asr = !!track && track.kind === "asr";
  const lines: Line[] = [];
  if (cur > 0) {
    const prev = cues[cur - 1]!;
    /* Authored cues roll up only from a back-to-back predecessor; one from before a gap stays gone. */
    if (asr || prev.startMs + prev.durationMs >= c.startMs - ROLL_UP_GAP_MS) {
      lines.push({ id: prev.id, text: cueRows(fullText(prev)) });
    }
  }
  lines.push({ id: c.id, text: cueRows(prefixOf(c, nowMs)) });
  return lines;
}

const ROLL_UP_GAP_MS = 250;

function fullText(cue: Cue): string {
  let text = "";
  for (let i = 0; i < cue.words.length; i++) text += cue.words[i]!.text;
  return text;
}

/* Keeps the cue's own line breaks; the overlay wraps and lays out the rows. */
function cueRows(text: string): string {
  const rows: string[] = [];
  const parts = text.split("\n");
  for (let i = 0; i < parts.length; i++) {
    const row = parts[i]!.trim();
    if (row.length) rows.push(row);
  }
  return rows.join("\n");
}

function nextTickMs(nowMs: number): number | null {
  if (!cues.length) return null;
  let cur = -1;
  for (let i = 0; i < cues.length; i++) {
    if (cues[i]!.startMs <= nowMs) cur = i;
    else break;
  }
  let soon: number | null = null;
  function consider(t: number): void {
    if (t <= nowMs) return;
    if (soon == null || t < soon) soon = t;
  }
  if (cur < 0) {
    consider(cues[0]!.startMs);
    return soon;
  }
  const c = cues[cur]!;
  for (let i = 0; i < c.words.length; i++) {
    consider(c.startMs + c.words[i]!.tOffsetMs);
  }
  consider(c.startMs + c.durationMs);
  if (cur + 1 < cues.length) consider(cues[cur + 1]!.startMs);
  return soon;
}

function nowMs(): number {
  return Math.round(__mseCurrentTime() * 1000);
}

function stopTimer(): void {
  if (tickTimer != null) {
    clearTimeout(tickTimer);
    tickTimer = null;
  }
}

async function emitWindow(): Promise<void> {
  const sink = sessionSink;
  if (!sink) return;
  const lines = selectedVss ? windowAt(nowMs()) : [];
  const rec: Record<string, unknown> = { "lines": lines };
  const js = JSON.stringify(rec);
  if (js === lastLinesJson) return;
  lastLinesJson = js;
  const rows: Record<string, unknown>[] = [];
  for (let i = 0; i < lines.length; i++) {
    rows.push({ "id": lines[i]!.id, "text": lines[i]!.text });
  }
  const p = sink.writeObjectAsJson({ "lines": rows });
  if (p) await p;
  const f = sink.flush();
  if (f) await f;
  debug(function () {
    return "caption emit n=" + rows.length;
  });
}

/* The session can open before the first load creates the media element; it follows whichever is active. */
let boundMedia: ReturnType<typeof activeHtmlMedia> = null;

function bindMedia(): void {
  const el = activeHtmlMedia();
  if (!sessionSink || el === boundMedia) return;
  unbindMedia();
  if (!el) return;
  el["addEventListener"]("timeupdate", onCaptionSeek);
  el["addEventListener"]("play", onCaptionPlay);
  el["addEventListener"]("pause", onCaptionPause);
  boundMedia = el;
}

function unbindMedia(): void {
  const el = boundMedia;
  if (!el) return;
  boundMedia = null;
  el["removeEventListener"]("timeupdate", onCaptionSeek);
  el["removeEventListener"]("play", onCaptionPlay);
  el["removeEventListener"]("pause", onCaptionPause);
}

onActiveHtmlMedia(function () {
  if (!sessionSink) return;
  bindMedia();
  void emitWindow().then(armTimer);
});

function mediaPaused(): boolean {
  const v = activeHtmlMedia();
  return !v || !!v["paused"];
}

function armTimer(): void {
  stopTimer();
  if (!sessionSink || !selectedVss || !cues.length || mediaPaused()) return;
  const n = nowMs();
  const next = nextTickMs(n);
  if (next == null) return;
  tickTimer = setTimeout(
    function () {
      tickTimer = null;
      void emitWindow().then(armTimer);
    },
    Math.max(16, next - n),
  );
}

function onCaptionPlay(): void {
  if (!sessionSink) return;
  void emitWindow().then(armTimer);
}

function onCaptionPause(): void {
  stopTimer();
  void emitWindow();
}

/* YouTube refuses auto-translate fetches (429, or 200 with an empty body); the source language beats no captions. */
async function reloadCues(untranslated = false): Promise<void> {
  stopTimer();
  cues = [];
  lastLinesJson = "";
  if (!sessionSink) return;
  if (!selectedVss) {
    await emitWindow();
    return;
  }
  const tr = findTrack(selectedVss);
  if (!tr) {
    await emitWindow();
    return;
  }
  const g = ++fetchGen;
  let url = withFmtJson3(tr.baseUrl);
  const translate = !!tr.tlang && !untranslated;
  if (translate) url = setQuery(url, "tlang", tr.tlang!);
  if (mintPo && videoId) {
    try {
      const pot = await mintPo(videoId);
      if (g !== fetchGen) return;
      if (pot) url = setQuery(url, "pot", pot);
    } catch (e) {
      console.warn("caption pot mint failed: " + (e instanceof Error ? e.message : String(e)));
    }
  }
  url = withCaptionClient(url);
  const referer = videoId
    ? "https://www.youtube.com/watch?v=" + videoId
    : "https://www.youtube.com/";
  const cver = ytcfgStr("INNERTUBE_CLIENT_VERSION");
  const visitor = ytcfgStr("VISITOR_DATA");
  debug(function () {
    return "caption timedtext GET " + queryFlags(url);
  });
  try {
    const req = HttpRequest.get(url)
      .setHeader("User-Agent", DESKTOP_UA)
      .setHeader("Accept", "*/*")
      .setHeader("Origin", "https://www.youtube.com")
      .setHeader("Referer", referer)
      .timeout(30000);
    if (cver) {
      req.setHeader("X-YouTube-Client-Name", "1");
      req.setHeader("X-YouTube-Client-Version", cver);
    }
    if (visitor) req.setHeader("X-Goog-Visitor-Id", visitor);
    const res = await executeWithCookies(url, req);
    if (g !== fetchGen) {
      res.close();
      return;
    }
    if (!res.ok) {
      console.warn(
        "caption timedtext HTTP " + res.status + (translate ? " tlang=" + tr.tlang : ""),
      );
      res.close();
      if (translate) return reloadCues(true);
      await emitWindow();
      return;
    }
    const body = await res.text();
    if (g !== fetchGen) return;
    cues = parseJson3(body);
    const ctype = res.headers.get("content-type") || "";
    if (cues.length) {
      debug(function () {
        return (
          "caption timedtext ok status=" +
          res.status +
          " bytes=" +
          body.length +
          " cues=" +
          cues.length +
          " ctype=" +
          ctype +
          " head=" +
          body.slice(0, 80).replace(/\n/g, " ")
        );
      });
    } else {
      console.warn(
        "caption timedtext empty" +
          (translate ? " tlang=" + tr.tlang : "") +
          " status=" +
          res.status +
          " bytes=" +
          body.length +
          " cues=" +
          cues.length +
          " ctype=" +
          ctype +
          " head=" +
          body.slice(0, 80).replace(/\n/g, " "),
      );
      if (translate) return reloadCues(true);
    }
  } catch (e) {
    if (g !== fetchGen) return;
    console.warn("caption timedtext failed: " + (e instanceof Error ? e.message : String(e)));
    if (translate) return reloadCues(true);
    await emitWindow();
    return;
  }
  await emitWindow();
  armTimer();
}

export function onCaptionSeek(): void {
  if (!sessionSink) return;
  stopTimer();
  void emitWindow().then(armTimer);
}

export async function runCaptionSession(pipe: Pipe): Promise<void> {
  if (sessionPipe) {
    throw new JsEngineErrorImpl("player/caption busy", "E20086");
  }
  sessionPipe = pipe;
  sessionSink = pipe.sink;
  lastLinesJson = "";
  try {
    await reloadCues();
    bindMedia();
    try {
      const r = pipe.source.readJsonObject();
      if (r && typeof (r as Promise<unknown>).then === "function") await r;
    } catch (_e) {
      /* host closed the session */
    }
  } finally {
    unbindMedia();
    stopTimer();
    fetchGen++;
    cues = [];
    sessionSink = null;
    sessionPipe = null;
    lastLinesJson = "";
  }
}
