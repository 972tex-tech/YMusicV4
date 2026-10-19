// InnerTube session bootstrap. The WEB client's clientVersion / visitorData / signatureTimestamp go
// stale, so we DON'T hardcode them (docs/innertube/auth.md): we scrape them from the youtube.com
// homepage's ytcfg the way the legacy app did, and cache them (in-memory + the host Cache).
// getSession(maxAge) re-scrapes only when the held record is older than maxAge (0 = always).
// A failed scrape falls back to the last known session unless the cache was cleared. The homepage
// fetch rides the cooked Cookie header (cookies.ts) and its Set-Cookie replies (VISITOR_INFO1_LIVE
// / YSC / consent) land back in the app's cookie store — required for a normal feed even
// logged-out. On a 403 the caller passes maxAge 0 and retries (params rotate server-side).

import { debug } from "../../debug";
import { jsLogger } from "../../protocol/logger";
import { JsEngineErrorImpl } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { Cache } from "../../protocol/cache";
import { executeWithCookies, pulledAppLanguage } from "./cookies";
import { parsePlayerConfig } from "./playerConfig";
import { anchoredExec, asString, get } from "./scan";

const ORIGIN = "https://www.youtube.com";
// Must match navigator.userAgent in orig/env.raw.js. Homepage / InnerTube / WAA send this
// as User-Agent; a different Chrome major than the page env attests is a fork. A bare UA
// gets a stripped homepage. JCEF's bundled Chromium major is CefManager.CHROME_USER_AGENT
// — do not copy that here.
export const DESKTOP_UA =
  "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 " +
  "(KHTML, like Gecko) Chrome/151.0.0.0 Safari/537.36";

const CACHE_SCOPE = "yt-session";
const CACHE_KEY = "web";
// The scraped homepage's ytInitialData payload, kept for yt/home's first load: it IS the home
// feed answer the page rendered from, so the bootstrap fetch can replace the first /browse call.
// Stored as UTF-8 from the assignment's `{` to the page end — the streaming parser stops at the
// value's end, so the page tail never needs delimiting. Single-use — consuming it deletes it (see
// consumeScrapedHomeData). An EMPTY entry marks a page that carried no payload: present-but-empty
// reads differently from consumed, so no re-scrape gets spent on it.
const HOMEPAGE_KEY = "homepage";
const PLAYER_CONFIG_KEY = "player-config";
// A homepage served from m.youtube.com (PREF app=m) is a mobile session with a BotGuard program the
// desktop player can't mint with — never parse or keep one.
const WWW_HOST = "www.youtube.com";
/** Default maxAge for feed/API calls: re-scrape when the held session is older than 6h. */
export const SESSION_MAX_AGE_MS = 6 * 60 * 60 * 1000;

/** The scraped WEB-client session (structure — dotted in-bundle; quoted only when it crosses to cache/wire). */
export interface YtSession {
  clientName: string; // numeric, e.g. "1"
  clientVersion: string; // e.g. "2.20260708.00.00"
  visitorData: string;
  apiKey: string | undefined;
  signatureTimestamp: string | undefined;
  // What YouTube says this connection IS, from its own homepage config — the server's answer
  // under the CURRENT cookie identity: with a locale pick in PREF these echo the pick, with none
  // the region is IP-derived and the language echoes the Accept-Language sent. "Selected" and
  // "detected" are the same value here.
  gl: string | undefined;
  hl: string | undefined;
  // The signed-in account's slot, scraped from ytcfg (legacy YTEssentialHeadersRefreshTask). The
  // account index feeds X-Goog-AuthUser (NOT a hardcoded "0"), the delegated id feeds X-Goog-PageId
  // for brand/delegated accounts. Absent = not signed in (or a single default account).
  sessionIndex: string | undefined;
  delegatedSessionId: string | undefined;
  // ytcfg USER_SESSION_ID — folded into each SAPISIDHASH input and marked with the `_u` suffix when
  // signed in (legacy getSAPISIDHASH). Absent = the plain (no-`_u`) hash form.
  userSessionId: string | undefined;
  fetchedAt: number; // epoch ms
}

let inMemory: YtSession | undefined;
let inMemoryPlayer: Record<string, unknown> | undefined;
// The ONE running scrape — getSession is called from every surface, and the homepage scrape is
// heavy enough that a burst of concurrent scrapes reads as bot traffic (BotGuard/captcha bait):
// concurrent callers JOIN the in-flight scrape instead of starting their own.
let inFlight: Promise<YtSession> | undefined;
// When the last scrape failed: callers holding a stale session reuse it until SCRAPE_RETRY_MS has
// passed instead of each paying a fresh scrape's timeout.
let failedAt = 0;
const SCRAPE_RETRY_MS = 5_000;

/**
 * The current session. Re-scrapes only when there is no held record or `now - fetchedAt` exceeds
 * `maxAge` (0 = always scrape). A failed scrape falls back to the last known session — expired
 * params usually still serve — and only throws when there has never been one. The scrape is
 * SINGLE-FLIGHT: concurrent callers share one. The Cache is best-effort — absent (no store wired)
 * or failing, we degrade to a live scrape, never an error.
 */
export async function getSession(maxAge: number): Promise<YtSession> {
  if (inMemory == null) {
    const cached = await readCache();
    if (cached != null) inMemory = cached;
  }
  const held = inMemory;
  if (held != null && maxAge > 0 && Date.now() - held.fetchedAt <= maxAge) return held;
  if (held != null && maxAge > 0 && Date.now() - failedAt < SCRAPE_RETRY_MS) return held;

  try {
    return await scrapeInFlight();
  } catch (e) {
    const stale = inMemory ?? (await readCache());
    if (stale != null) {
      inMemory = stale;
      debug(function () {
        return "yt session scrape failed, serving the stale session: " + errText(e);
      });
      return stale;
    }
    throw e;
  }
}

/** The one running scrape, started if none is — concurrent waiters share it. */
function scrapeInFlight(): Promise<YtSession> {
  console.info("DBG scrapeInFlight joining=" + (inFlight != null));
  if (inFlight == null) {
    inFlight = (async () => {
      try {
        let scraped: YtSession;
        try {
          scraped = await scrapeSession();
        } catch (e) {
          failedAt = Date.now();
          throw e;
        }
        failedAt = 0;
        inMemory = scraped;
        await writeCache(scraped);
        return scraped;
      } finally {
        inFlight = undefined;
      }
    })();
  }
  return inFlight;
}

/**
 * The pair every YouTube surface speaks: the app language (cookies.ts, cached settings pull) and
 * the SESSION's parsed gl — the working region value YouTube controls (a pick seeds it via PREF +
 * refresh, but the parsed answer always lands last). The session lookup is best-effort — offline
 * with a cold session, the language still answers.
 */
export async function effectiveLocale(): Promise<{ hl: string; gl: string }> {
  let hl = await pulledAppLanguage();
  let gl: string | undefined;
  let s: YtSession | undefined;
  try {
    s = await getSession(Infinity);
  } catch (e) {
    s = undefined;
  }
  if (hl == null && s != null && s.hl != null && s.hl.length > 0) hl = s.hl;
  if (s != null && s.gl != null && s.gl.length > 0) gl = s.gl;
  return { hl: hl ?? "en", gl: (gl ?? "US").toUpperCase() };
}

/**
 * Temporarily stamp a fresh pick onto the cached session while [getSession]`(0)` re-scrapes —
 * concurrent requests speak the new locale before the server's echo confirms it. Blank halves
 * ("automatic") keep the old value until the refresh answers with the real automatic one.
 */
export async function overrideSessionLocale(hl: string, gl: string): Promise<void> {
  const held = inMemory;
  if (held == null) return;
  if (hl.length > 0) held.hl = hl;
  if (gl.length > 0) held.gl = gl;
  await writeCache(held);
}

/**
 * Drop the bootstrapped session (in-memory + host Cache) so the next call re-scrapes. Needed when
 * the cookie identity changes underneath it (a locale override written to PREF): the cached
 * session's GL/HL answer the OLD identity.
 */
export async function invalidateSession(): Promise<void> {
  inMemory = undefined;
  inMemoryPlayer = undefined;
  try {
    await Cache.delete(CACHE_SCOPE, CACHE_KEY);
    // The kept homepage answered the old identity too.
    await Cache.delete(CACHE_SCOPE, HOMEPAGE_KEY);
    await Cache.delete(CACHE_SCOPE, PLAYER_CONFIG_KEY);
  } catch (e) {
    debug(function () {
      return "yt session cache clear skipped: " + errText(e);
    });
  }
}

/**
 * Consume the kept ytInitialData — a SINGLE-USE read: the entry is deleted with the read, so a
 * stale page can never serve the home feed twice. `found` answers "was there an entry at all"
 * (false → the caller may spend ONE forced re-scrape); `data` is the parsed payload — the very
 * browse answer the homepage rendered from, the same shape /browse returns, so collectPage reads
 * it unchanged — absent on the empty no-payload marker or a payload that doesn't parse (found,
 * but only /browse can answer). The payload is stream-parsed straight off the cache pipe with the
 * native push parser: no byte buffer, and once the top-level value closes the page tail behind it
 * is never even read. A RUNNING scrape is settled first — it is about to (re)write the entry, so
 * the read sees the freshest completed state — and having settled one counts as `found` even on a
 * miss: a scrape JUST ran, so the caller's forced refresh could only repeat what we watched
 * (a success already wrote the entry; a failure would just fail again). A settle that failed with
 * no session anywhere is the terminal cold-start error and RETHROWS.
 */
export async function consumeScrapedHomeData(): Promise<{
  found: boolean;
  data?: Record<string, unknown>;
}> {
  let settled = false;
  if (inFlight != null) {
    settled = true; // a scrape JUST ran either way — the caller must never spend another
    try {
      await inFlight;
    } catch (e) {
      const stale = inMemory ?? (await readCache());
      if (stale == null) throw e;
      inMemory = stale; // the entry (if any) still answers, and /browse rides the stale session
    }
  }
  let read: { data?: Record<string, unknown> } | null = null;
  try {
    read = await Cache.get(CACHE_SCOPE, HOMEPAGE_KEY, async (source, meta) => {
      // The no-payload marker: reads as found (non-null) with no data — never a re-scrape.
      if (meta.size === 0) return {};
      try {
        const p = __jsonNew(1); // 1 = the root must be an object
        const chunk = new Uint8Array(8192);
        for (;;) {
          const n = await source.receive(chunk, 0, chunk.length);
          if (n < 0) break; // EOF — __jsonEnd throws below if the value never completed
          if (__jsonPush(p, chunk, 0, n) < n) break; // value complete — the tail stays unread
        }
        return { data: __jsonEnd(p) as Record<string, unknown> };
      } catch (e) {
        return {}; // malformed or truncated — found, but nothing to serve
      }
    });
  } catch (e) {
    debug(function () {
      return "yt homepage cache read skipped: " + errText(e);
    });
    return { found: settled }; // just-scraped counts as found: a re-scrape can't cure a dead cache
  }
  if (read == null) return { found: settled };
  try {
    await Cache.delete(CACHE_SCOPE, HOMEPAGE_KEY);
  } catch (e) {
    debug(function () {
      return "yt homepage cache delete skipped: " + errText(e);
    });
  }
  return read.data == null ? { found: true } : { found: true, data: read.data };
}

/**
 * Where the homepage's inline `ytInitialData` payload starts — the assignment's `{` — or -1 when
 * the page carries none (a consent shim, a stripped page).
 */
function initialDataStart(html: string): number {
  let from = 0;
  for (;;) {
    const at = html.indexOf("ytInitialData", from);
    if (at === -1) return -1;
    from = at + "ytInitialData".length;
    const brace = html.indexOf("{", from);
    if (brace === -1) return -1;
    // Only assignment glue may sit between the name and its object (`ytInitialData = {`,
    // `window["ytInitialData"] = {`) — anything else is a mention, not the payload. Glue is a
    // handful of chars; on a false anchor the next `{` can be megabytes away, so bound the gap
    // BEFORE slicing it for the regex.
    if (brace - from > 16) continue;
    if (RE_INITIAL_GLUE.test(html.slice(from, brace))) return brace;
  }
}

const RE_INITIAL_GLUE = /^['"\]\s]*=\s*$/;

// CLDR likely region per app language (the app's legacy "iw" is Chrome's "he").
const LIKELY_REGION: Record<string, string> = {
  "en": "US",
  "af": "ZA",
  "az": "AZ",
  "id": "ID",
  "ms": "MY",
  "ca": "ES",
  "cs": "CZ",
  "de": "DE",
  "et": "EE",
  "es": "ES",
  "eu": "ES",
  "fil": "PH",
  "fr": "FR",
  "hr": "HR",
  "sw": "TZ",
  "hu": "HU",
  "nl": "NL",
  "pl": "PL",
  "pt": "BR",
  "ro": "RO",
  "sk": "SK",
  "so": "SO",
  "it": "IT",
  "vi": "VN",
  "tr": "TR",
  "el": "GR",
  "bg": "BG",
  "mk": "MK",
  "ru": "RU",
  "sr": "RS",
  "uk": "UA",
  "hy": "AM",
  "he": "IL",
  "ar": "EG",
  "fa": "IR",
  "ne": "NP",
  "mr": "IN",
  "hi": "IN",
  "bn": "BD",
  "gu": "IN",
  "ta": "IN",
  "kn": "IN",
  "ml": "IN",
  "si": "LK",
  "th": "TH",
  "ko": "KR",
  "ja": "JP",
  "zh": "CN",
};

/** The Accept-Language Chrome sends for a UI locale: "<lang>-<REGION>,<lang>,en-US,en" with
 *  descending q, deduplicated. navigator.language(s) are parsed back from it. */
function chromeAcceptLanguage(appLanguage: string | undefined): string {
  const parts = (appLanguage ?? "en").split("-");
  const raw = (parts[0] ?? "en").toLowerCase();
  const lang = raw === "iw" ? "he" : raw === "in" ? "id" : raw;
  const region = parts[1] !== undefined ? parts[1].toUpperCase() : LIKELY_REGION[lang];
  const tags: string[] = [];
  for (const tag of [region !== undefined ? lang + "-" + region : lang, lang, "en-US", "en"]) {
    if (tags.indexOf(tag) < 0) tags.push(tag);
  }
  return tags
    .map(function (tag, i) {
      return i === 0 ? tag : tag + ";q=" + (1 - i / 10).toFixed(1);
    })
    .join(",");
}

async function scrapeSession(): Promise<YtSession> {
  // The app language rides Accept-Language (next to PREF), so the homepage answers in it and its
  // ytcfg HL echoes the effective value back into this session.
  const hl = await pulledAppLanguage();
  console.info("DBG scrape hl pulled");
  const acceptLanguage = chromeAcceptLanguage(hl);
  const res = await executeWithCookies(
    ORIGIN + "/",
    HttpRequest.get(ORIGIN + "/")
      .setHeader("User-Agent", DESKTOP_UA)
      .setHeader("Accept", "text/html")
      .setHeader("Accept-Language", acceptLanguage)
      .logger(jsLogger("yt/session")),
  );
  const challenge = challengeUrl(res);
  if (challenge !== undefined) {
    res.close();
    throw JsEngineErrorImpl.captcha("yt session: bot challenge at " + challenge);
  }
  if (!res.ok) {
    if (RE_CHALLENGE_BODY.test(await res.text()))
      throw JsEngineErrorImpl.captcha(
        "yt session: bot challenge page (" + res.status + ") at " + res.url,
      );
    throw JsEngineErrorImpl.httpStatus("yt session", res.status, "E20145");
  }
  const host = hostOf(res.url);
  if (host !== WWW_HOST) {
    res.close();
    const message = "yt session: homepage redirected to " + host;
    // The signed-in session is dead: the playability code every host already offers sign-in for.
    if (host === "accounts.google.com") throw new JsEngineErrorImpl(message, "LOGIN_REQUIRED");
    // A Family Link (supervised) account: YouTube sends it to the parent-setup site instead.
    if (host === "families.youtube.com") throw new JsEngineErrorImpl(message, "supervised_account");
    throw JsEngineErrorImpl.parse(message);
  }
  const html = await res.text();
  if (RE_CHALLENGE_BODY.test(html))
    throw JsEngineErrorImpl.captcha("yt session: bot challenge page at " + res.url);
  if (html.indexOf("INNERTUBE_") === -1)
    throw JsEngineErrorImpl.parse("yt session: no ytcfg in homepage");

  const clientVersion = firstMatch(html, "INNERTUBE_CONTEXT_CLIENT_VERSION", RE_CLIENT_VERSION);
  const visitorData = firstMatch(html, "VISITOR_DATA", RE_VISITOR_DATA, -1);
  if (clientVersion == null) throw JsEngineErrorImpl.parse("yt session: no clientVersion");
  if (visitorData == null) throw JsEngineErrorImpl.parse("yt session: no visitorData");

  // Keep the page's initial-data payload for yt/home's first load (empty = the page had none) —
  // best-effort, like every cache touch here. writeUtf8Range encodes straight from the string's
  // storage: no payload slice materializes.
  const feedStart = initialDataStart(html);
  try {
    await Cache.put(CACHE_SCOPE, HOMEPAGE_KEY, (sink) => {
      if (feedStart !== -1) return sink.writeUtf8Range(html, feedStart, html.length);
    });
  } catch (e) {
    debug(function () {
      return "yt homepage cache write skipped: " + errText(e);
    });
  }

  await storePlayerConfig(html, acceptLanguage);

  return {
    clientName: firstMatch(html, "INNERTUBE_CONTEXT_CLIENT_NAME", RE_CLIENT_NAME) ?? "1",
    clientVersion: clientVersion,
    visitorData: visitorData,
    apiKey: firstMatch(html, "INNERTUBE_API_KEY", RE_API_KEY),
    signatureTimestamp:
      firstMatch(html, "STS", RE_STS, -1) ?? firstMatch(html, "signatureTimestamp", RE_STS, -1),
    gl: firstMatch(html, "GL", RE_GL, -1),
    hl: firstMatch(html, "HL", RE_HL, -1),
    sessionIndex:
      firstMatch(html, "SESSION_INDEX", RE_SESSION_INDEX, -1) ??
      firstMatch(html, "sessionIndex", RE_SESSION_INDEX, -1),
    delegatedSessionId:
      (firstMatch(html, "DELEGATED_SESSION_ID", RE_DELEGATED_SESSION, -1) ??
        firstMatch(html, "delegatedSessionId", RE_DELEGATED_SESSION, -1)) ||
      undefined,
    userSessionId: firstMatch(html, "USER_SESSION_ID", RE_USER_SESSION_ID, -1) || undefined,
    fetchedAt: Date.now(),
  };
}

// ytcfg scrape regexes — the legacy YtClientStateHelper patterns, ported. The homepage embeds these
// as JS object literals, so the value can be single- or double-quoted with loose whitespace.
// All STICKY (/y), pinned to indexOf hits of their literal by anchoredExec (see scan.ts): the
// ~2MB homepage never gets a full regex scan. Quote-prefixed patterns anchor at offset -1.
const RE_CLIENT_NAME = /INNERTUBE_CONTEXT_CLIENT_NAME["']?\s*:\s*["']?(\d+)/y;
const RE_CLIENT_VERSION = /INNERTUBE_CONTEXT_CLIENT_VERSION["']?\s*:\s*["']([^"']+)["']/y;
const RE_API_KEY = /INNERTUBE_API_KEY["']?\s*:\s*["']([^"']+)["']/y;
const RE_STS = /["'](?:STS|signatureTimestamp)["']\s*:\s*(\d+)/iy;
const RE_VISITOR_DATA = /["']VISITOR_DATA["']\s*:\s*["']([^"']*)["']/y;
const RE_GL = /["']GL["']\s*:\s*["']([A-Za-z]{2})["']/y;
const RE_HL = /["']HL["']\s*:\s*["']([A-Za-z-]+)["']/y;
// SESSION_INDEX is a bare or quoted integer; DELEGATED_SESSION_ID a quoted string (empty when none).
const RE_SESSION_INDEX = /["'](?:SESSION_INDEX|sessionIndex)["']\s*:\s*["']?(\d+)/y;
const RE_DELEGATED_SESSION =
  /["'](?:DELEGATED_SESSION_ID|delegatedSessionId)["']\s*:\s*["']([^"']*)["']/y;
const RE_USER_SESSION_ID = /["']USER_SESSION_ID["']\s*:\s*["']([^"']*)["']/y;

function firstMatch(s: string, literal: string, re: RegExp, offset = 0): string | undefined {
  const m = anchoredExec(s, literal, re, offset);
  return m == null ? undefined : m[1];
}

// ---- host Cache (best-effort persistence across restarts) ----
// The session is DATA when it crosses to the cache as JSON, so quoted keys (Closure never renames
// them) — otherwise the round-tripped record wouldn't match the reader's bracket access.

function toRecord(s: YtSession): Record<string, unknown> {
  const r: Record<string, unknown> = {
    "clientName": s.clientName,
    "clientVersion": s.clientVersion,
    "visitorData": s.visitorData,
    "fetchedAt": s.fetchedAt,
  };
  if (s.apiKey != null) r["apiKey"] = s.apiKey;
  if (s.signatureTimestamp != null) r["signatureTimestamp"] = s.signatureTimestamp;
  if (s.gl != null) r["gl"] = s.gl;
  if (s.hl != null) r["hl"] = s.hl;
  if (s.sessionIndex != null) r["sessionIndex"] = s.sessionIndex;
  if (s.delegatedSessionId != null) r["delegatedSessionId"] = s.delegatedSessionId;
  if (s.userSessionId != null) r["userSessionId"] = s.userSessionId;
  return r;
}

function fromRecord(o: unknown): YtSession | undefined {
  const clientVersion = asString(get(o, "clientVersion"));
  const visitorData = asString(get(o, "visitorData"));
  const fetchedAt = get(o, "fetchedAt");
  if (clientVersion == null || visitorData == null || typeof fetchedAt !== "number") {
    return undefined;
  }
  return {
    clientName: asString(get(o, "clientName")) ?? "1",
    clientVersion: clientVersion,
    visitorData: visitorData,
    apiKey: asString(get(o, "apiKey")),
    signatureTimestamp: asString(get(o, "signatureTimestamp")),
    gl: asString(get(o, "gl")),
    hl: asString(get(o, "hl")),
    sessionIndex: asString(get(o, "sessionIndex")),
    delegatedSessionId: asString(get(o, "delegatedSessionId")),
    userSessionId: asString(get(o, "userSessionId")),
    fetchedAt: fetchedAt,
  };
}

async function readCache(): Promise<YtSession | undefined> {
  try {
    const raw = await Cache.getJson(CACHE_SCOPE, CACHE_KEY);
    return raw == null ? undefined : fromRecord(raw);
  } catch (e) {
    // No cache store wired (capability inert) or a store error — a live scrape still works.
    debug(function () {
      return "yt session cache read skipped: " + errText(e);
    });
    return undefined;
  }
}

async function writeCache(s: YtSession): Promise<void> {
  try {
    await Cache.setJson(CACHE_SCOPE, CACHE_KEY, toRecord(s));
  } catch (e) {
    debug(function () {
      return "yt session cache write skipped: " + errText(e);
    });
  }
}

// Google's bot gate: a redirect to google.com/sorry (followed, or a bare 3xx when redirects are
// off), or a 429 straight from the homepage. Answers the challenge URL, undefined when none.
function challengeUrl(res: HttpResponse): string | undefined {
  if (isSorryUrl(res.url)) return res.url;
  if (res.status >= 300 && res.status < 400) {
    const location = res.headers.get("location");
    if (location != null && isSorryUrl(location)) return location;
  }
  if (res.status === 429) return res.url.length > 0 ? res.url : ORIGIN + "/";
  return undefined;
}

function isSorryUrl(url: string): boolean {
  const host = hostOf(url);
  if (host !== "google.com" && host !== "www.google.com") return false;
  return url.indexOf("/sorry", url.indexOf(host) + host.length) !== -1;
}

// The reCAPTCHA v2 widget element. The normal homepage mentions recaptcha too (RECAPTCHA_V3_SITEKEY,
// .grecaptcha-badge) but never carries this class attribute.
const RE_CHALLENGE_BODY = /\bclass\s*=\s*["'](?:[^"']*\s)?g-recaptcha["'\s]/i;

function hostOf(url: string): string {
  const start = url.indexOf("//");
  const rest = start < 0 ? url : url.slice(start + 2);
  const end = rest.search(/[/?#]/);
  return end < 0 ? rest : rest.slice(0, end);
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

async function storePlayerConfig(html: string, acceptLanguage: string): Promise<void> {
  const cfg = parsePlayerConfig(html);
  if (cfg == null) {
    console.info("yt session: homepage has no player config");
    return;
  }
  // The player's navigator.languages must match the Accept-Language this homepage was fetched with.
  cfg["acceptLanguage"] = acceptLanguage;
  inMemoryPlayer = cfg;
  try {
    await Cache.setJson(CACHE_SCOPE, PLAYER_CONFIG_KEY, cfg);
  } catch (e) {
    debug(function () {
      return "yt player config cache write skipped: " + errText(e);
    });
  }
}

/** Memory then host Cache. Null when this session never saw a homepage with BotGuard/jsUrl. */
export async function peekPlayerConfig(): Promise<Record<string, unknown> | undefined> {
  if (inMemoryPlayer != null) return inMemoryPlayer;
  try {
    const raw = await Cache.getJson(CACHE_SCOPE, PLAYER_CONFIG_KEY);
    if (raw != null && typeof raw === "object") {
      const rec = raw as Record<string, unknown>;
      if (typeof rec["jsUrl"] === "string" && rec["bgChallenge"] != null) {
        inMemoryPlayer = rec;
        return rec;
      }
    }
  } catch (e) {
    debug(function () {
      return "yt player config cache read skipped: " + errText(e);
    });
  }
  return undefined;
}
