// The Settings pickers' data — the pickable content-language / content-region menus plus where the
// locale currently stands. The selected pair is the ENGINE's own state (the applied pick from
// yt/locale/apply; the session's ytcfg echo answers for "automatic"); the account menu is used
// only for what it is good at: the pickable lists, labelled in the effective language (it can NOT
// report the connection's region — it just echoes whatever context the request carried, verified
// live 2026-07-25).
//
// The menus are asked for only when the Settings pickers open, so ONE cached copy (in-memory + host
// Cache) is enough: it records the hl/gl pair it was fetched under, and a request re-fetches only
// when the effective pair changed (labels are localized to hl) or the copy is over a week old —
// falling back to the cached copy when the re-fetch fails, because year-stable lists in
// yesterday's language beat an error dialog.

import { debug } from "../../debug";
import { innertubeJson } from "./innertube";
import { effectiveLocale } from "./session";
import { Cache } from "../../protocol/cache";
import { asString, get, textOf, walk } from "./scan";
import type { WireLocaleOption, WireLocales } from "./wire";

const CACHE_SCOPE = "yt-locales";
const CACHE_KEY = "menus";
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000; // menus drift on the order of years; labels only with hl

/** account_menu's contribution, separated from the live locale state so it can be cached alone. */
interface Menus {
  hl: string; // the pair the menus were fetched under — the labels' language
  gl: string;
  languages: WireLocaleOption[];
  regions: WireLocaleOption[];
  fetchedAt: number; // epoch ms
}

let inMemory: Menus | undefined;

/**
 * Everything the Settings region picker needs: the WORKING region (the session's parsed answer —
 * YouTube's own, whether IP-derived or following a pick) and the menu YouTube offers, labelled in
 * the effective language. There is no "automatic" state to report: the working value is always a
 * concrete region, and picking one is a plain yt/locale/apply write. (The language is not here —
 * the app language IS the content language, picked in the app's own language row.)
 */
export async function localeOptions(): Promise<WireLocales> {
  const effective = await effectiveLocale();
  const menus = await getMenus(effective.hl, effective.gl);
  return {
    "gl": effective.gl,
    "languages": menus.languages,
    "regions": menus.regions,
  };
}

async function getMenus(hl: string, gl: string): Promise<Menus> {
  const held = inMemory ?? (await readCache());
  inMemory = held;
  if (
    held !== undefined &&
    held.hl === hl &&
    held.gl === gl &&
    Date.now() - held.fetchedAt < MAX_AGE_MS
  ) {
    return held;
  }

  let scraped: Menus;
  try {
    scraped = await scrapeMenus(hl, gl);
  } catch (e) {
    if (held === undefined) throw e;
    debug(function () {
      return "yt locales fetch failed, serving the cached menus: " + errText(e);
    });
    return held;
  }
  // An empty menu means a stripped/walled response, not "YouTube offers nothing" — serve it once
  // but never cache it, so the next ask retries.
  if (scraped.languages.length > 0 && scraped.regions.length > 0) {
    inMemory = scraped;
    await writeCache(scraped);
  }
  return scraped;
}

async function scrapeMenus(hl: string, gl: string): Promise<Menus> {
  const menus: Menus = {
    hl: hl,
    gl: gl,
    languages: [],
    regions: [],
    fetchedAt: Date.now(),
  };
  const root = await innertubeJson("account/account_menu", {});
  walk(root, (key, value) => {
    if (key !== "compactLinkRenderer") return;
    const label = textOf(get(value, "title"));
    const actions = get(value, "serviceEndpoint", "signalServiceEndpoint", "actions");
    if (label === undefined || !Array.isArray(actions)) return;
    for (const action of actions) {
      const optHl = asString(get(action, "selectLanguageCommand", "hl"));
      if (optHl !== undefined) menus.languages.push({ "code": optHl, "label": label });
      const optGl = asString(get(action, "selectCountryCommand", "gl"));
      if (optGl !== undefined) menus.regions.push({ "code": optGl, "label": label });
    }
  });
  return menus;
}

// ---- host Cache (best-effort persistence across restarts) ----
// The menus are DATA when they cross to the cache as JSON, so quoted keys (Closure never renames
// them) — otherwise the round-tripped record wouldn't match the reader's bracket access.

function toRecord(m: Menus): Record<string, unknown> {
  return {
    "hl": m.hl,
    "gl": m.gl,
    "languages": m.languages,
    "regions": m.regions,
    "fetchedAt": m.fetchedAt,
  };
}

function fromRecord(o: unknown): Menus | undefined {
  const hl = asString(get(o, "hl"));
  const gl = asString(get(o, "gl"));
  const fetchedAt = get(o, "fetchedAt");
  const languages = readOptions(get(o, "languages"));
  const regions = readOptions(get(o, "regions"));
  if (
    hl === undefined ||
    gl === undefined ||
    typeof fetchedAt !== "number" ||
    languages === undefined ||
    regions === undefined
  ) {
    return undefined;
  }
  return {
    hl: hl,
    gl: gl,
    languages: languages,
    regions: regions,
    fetchedAt: fetchedAt,
  };
}

function readOptions(v: unknown): WireLocaleOption[] | undefined {
  if (!Array.isArray(v)) return undefined;
  const out: WireLocaleOption[] = [];
  for (const item of v) {
    const code = asString(get(item, "code"));
    const label = asString(get(item, "label"));
    if (code === undefined || label === undefined) return undefined;
    out.push({ "code": code, "label": label });
  }
  return out;
}

async function readCache(): Promise<Menus | undefined> {
  try {
    const raw = await Cache.getJson(CACHE_SCOPE, CACHE_KEY);
    return raw === null ? undefined : fromRecord(raw);
  } catch (e) {
    // No cache store wired (capability inert) or a store error — a live scrape still works.
    debug(function () {
      return "yt locales cache read skipped: " + errText(e);
    });
    return undefined;
  }
}

async function writeCache(m: Menus): Promise<void> {
  try {
    await Cache.setJson(CACHE_SCOPE, CACHE_KEY, toRecord(m));
  } catch (e) {
    debug(function () {
      return "yt locales cache write skipped: " + errText(e);
    });
  }
}

function errText(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}
