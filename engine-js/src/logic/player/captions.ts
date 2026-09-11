// YouTube timedtext (json3) → 2-line paint-on window. Host opens player/caption to subscribe.

import { JsEngineErrorImpl } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { executeWithCookies } from "../yt/cookies";
import { DESKTOP_UA } from "../yt/session";
import { activeHtmlMedia } from "../../protocol/mse";

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
  if (selectedVss) {
    let ok = false;
    for (let i = 0; i < tracks.length; i++) {
      if (tracks[i]!.vssId === selectedVss) ok = true;
    }
    if (!ok) selectedVss = null;
  }
  cues = [];
  console.info("caption ingest tracks=" + out.length + " vid=" + videoId);
  noteIngest();
  if (sessionSink) void reloadCues();
}

export function listCaptionTracks(): Record<string, unknown> {
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
  return { "tracks": rows };
}

export function selectedCaptionVss(): string | null {
  return selectedVss;
}

export function setCaptionVss(vss: string | null): void {
  selectedVss = vss && vss.length ? vss : null;
  cues = [];
  if (sessionSink) void reloadCues();
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
  if (hasQuery(url, "fmt")) return url.replace(/([?&])fmt=[^&]*/, "$1fmt=json3");
  return url + (url.indexOf("?") >= 0 ? "&" : "?") + "fmt=json3";
}

function withQuery(url: string, key: string, val: string): string {
  if (hasQuery(url, key)) return url;
  return url + (url.indexOf("?") >= 0 ? "&" : "?") + key + "=" + encodeURIComponent(val);
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

/** Chrome timedtext adds potc/c/cver; without them YouTube returns HTTP 200 and an empty body. */
function withCaptionClient(url: string): string {
  url = withQuery(url, "potc", "1");
  url = withQuery(url, "c", "WEB");
  url = withQuery(url, "cplayer", "UNIPLAYER");
  const cver = ytcfgStr("INNERTUBE_CLIENT_VERSION");
  if (cver) url = withQuery(url, "cver", cver);
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
    (hasQuery(url, "c") ? "1" : "0")
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
      if (utf === "\n") {
        if (full.length && full.charAt(full.length - 1) !== " ") {
          words.push({ tOffsetMs: tOff, text: " " });
          full += " ";
        }
        continue;
      }
      words.push({ tOffsetMs: tOff, text: utf });
      full += utf;
    }
    if (!full.length) continue;
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
  if (ended && (!next || next.startMs > nowMs)) return [];
  const lines: Line[] = [];
  if (cur > 0) {
    const prev = cues[cur - 1]!;
    let full = "";
    for (let i = 0; i < prev.words.length; i++) full += prev.words[i]!.text;
    lines.push({ id: prev.id, text: full });
  }
  lines.push({ id: c.id, text: prefixOf(c, nowMs) });
  if (lines.length > 2) return lines.slice(lines.length - 2);
  return lines;
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
  if (MSE_DEBUG) console.log("caption emit n=" + rows.length);
}

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

async function reloadCues(): Promise<void> {
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
  if (tr.tlang) url = withQuery(url, "tlang", tr.tlang);
  if (mintPo && videoId) {
    try {
      const pot = await mintPo(videoId);
      if (g !== fetchGen) return;
      if (pot) url = withQuery(url, "pot", pot);
    } catch (_e) {}
  }
  url = withCaptionClient(url);
  const referer = videoId
    ? "https://www.youtube.com/watch?v=" + videoId
    : "https://www.youtube.com/";
  const cver = ytcfgStr("INNERTUBE_CLIENT_VERSION");
  const visitor = ytcfgStr("VISITOR_DATA");
  if (MSE_DEBUG) console.log("caption timedtext GET " + queryFlags(url));
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
      res.ignoreResult();
      return;
    }
    if (!res.ok) {
      console.warn("caption timedtext HTTP " + res.status);
      res.ignoreResult();
      await emitWindow();
      return;
    }
    const body = await res.text();
    if (g !== fetchGen) return;
    cues = parseJson3(body);
    const ctype = res.headers.get("content-type") || "";
    if (MSE_DEBUG)
      console.log(
        "caption timedtext ok status=" +
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
  } catch (e) {
    if (g !== fetchGen) return;
    console.warn("caption timedtext failed: " + (e instanceof Error ? e.message : String(e)));
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
    const v = activeHtmlMedia();
    if (v) {
      v["addEventListener"]("timeupdate", onCaptionSeek);
      v["addEventListener"]("play", onCaptionPlay);
      v["addEventListener"]("pause", onCaptionPause);
    }
    try {
      const r = pipe.source.readJsonObject();
      if (r && typeof (r as Promise<unknown>).then === "function") await r;
    } catch (_e) {
      /* host closed the session */
    }
  } finally {
    const el = activeHtmlMedia();
    if (el) {
      el["removeEventListener"]("timeupdate", onCaptionSeek);
      el["removeEventListener"]("play", onCaptionPlay);
      el["removeEventListener"]("pause", onCaptionPause);
    }
    stopTimer();
    fetchGen++;
    cues = [];
    sessionSink = null;
    sessionPipe = null;
    lastLinesJson = "";
  }
}
