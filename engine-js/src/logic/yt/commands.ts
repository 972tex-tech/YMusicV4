// The YouTube metadata commands the app calls (Kotlin twin: yt/YtCommands.kt in :dynamic-engine).
// Args head: one JSON object; result: one wire JSON value (wire.ts shapes) — command in, page out.

import { onAccept } from "../../transport";
import { JsEngineErrorImpl, commandHandler } from "../../protocol/command";
import { HttpRequest } from "../../protocol/http";
import { innertubeJson } from "./innertube";
import { hostLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";
import { trendingMusicPage } from "./charts";
import { applyLocaleCookie, invalidateAppLanguage, pulledAppLanguage } from "./cookies";
import {
  consumeScrapedHomeData,
  effectiveLocale,
  getSession,
  invalidateSession,
  overrideSessionLocale,
  peekPlayerConfig,
} from "./session";
import { localeOptions } from "./locales";
import { harvestCommand, recommendedPage } from "./recommended";
import {
  collectItems,
  collectPage,
  dedupeItems,
  isMixPlaylist,
  mixSeedVideoId,
  mixToken,
  parseChannelSortToken,
  parseMixToken,
  parseSearchChipToken,
  rankSearchItems,
} from "./parse";
import { asString, get } from "./scan";
import type { WireFeedPage, WireLocales, WireSuggest } from "./wire";

function putIfString(
  fields: Record<string, unknown>,
  args: Record<string, unknown>,
  name: string,
): boolean {
  const v = asString(args[name]);
  if (v === undefined) return false;
  fields[name] = v;
  return true;
}

async function writePage(out: PipeSink, page: WireFeedPage): Promise<void> {
  const p = out.writeObjectAsJson(page);
  if (p) await p;
}

// ---- The url round trip ------------------------------------------------------------------------
// The app is a dumb navigator: it holds only the app links this engine composed (item urls, tab
// urls, section-header CTAs — see parse.ts playlistUrl/channelUrl) and hands them back verbatim.
// Which browseIds/params/endpoints a link means — including engine-private query state like a Mix's
// `v=` seed — is decoded HERE, so a YouTube change never needs an app update.

/** The decoded query of an app link ("/playlist?list=…&v=…"); {} when there is none. */
function urlQuery(url: string): Record<string, string> {
  const out: Record<string, string> = {};
  const q = url.indexOf("?");
  if (q < 0) return out;
  for (const pair of url.slice(q + 1).split("&")) {
    const eq = pair.indexOf("=");
    if (eq <= 0) continue;
    out[decodeURIComponent(pair.slice(0, eq))] = decodeURIComponent(pair.slice(eq + 1));
  }
  return out;
}

async function browsePage(fields: Record<string, unknown>, log?: JsLogger): Promise<WireFeedPage> {
  return collectPage(await innertubeJson("browse", fields, log));
}

// The Home feed: {"continuation"?}. The engine owns the front-door browseId. The FIRST fresh load
// after engine start rides the session bootstrap's own homepage instead of /browse: the scraped
// page's ytInitialData is the same browse answer (scrapeSession keeps the HTML; single-use). Every
// later load — fresh or continuation — browses normally.
let homeBootstrapUsed = false;

const PLAYER_CONFIG_MAX_AGE_MS = 60 * 60 * 1000;

onAccept(
  "get_player_config",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const maxAge =
      typeof args["maxAge"] === "number" && args["maxAge"] >= 0
        ? (args["maxAge"] as number)
        : PLAYER_CONFIG_MAX_AGE_MS;
    await getSession(maxAge);
    const cfg = await peekPlayerConfig();
    if (cfg == null) throw JsEngineErrorImpl.parse("yt session: no player config in homepage");
    const p = out.writeObjectAsJson(cfg);
    if (p) await p;
  }),
);

onAccept(
  "yt/home",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    if (!putIfString(fields, args, "continuation")) {
      fields["browseId"] = "FEwhat_to_watch";
      if (!homeBootstrapUsed) {
        homeBootstrapUsed = true;
        const page = await bootstrapHomePage();
        if (page !== undefined) {
          await writePage(out, page);
          return;
        }
      }
    }
    await writePage(out, await browsePage(fields, hostLogger(args, "yt/home")));
  }),
);

/**
 * The first home feed, off the session bootstrap's homepage payload. The consume settles any
 * running scrape itself, so a kept entry serves the freshest completed page — and with none
 * running, straight off the disk, no session needed. A DEFINITIVE miss (consumed by a past run)
 * spends ONE forced session refresh, and that lone homepage fetch serves the session AND the
 * feed: a cold start still makes one request, just not /browse. The settle and the refresh only
 * throw when there has NEVER been a session (cold start, scrape failed); rethrown, since /browse
 * could only repeat the same failure. Undefined → the /browse fallback: no entry even after the
 * refresh, or the page carried no usable payload — not worth a second scrape.
 */
async function bootstrapHomePage(): Promise<WireFeedPage | undefined> {
  let home = await consumeScrapedHomeData();
  if (!home.found) {
    await getSession(0);
    home = await consumeScrapedHomeData();
  }
  return home.data == null ? undefined : collectPage(home.data);
}

// The signed-in Library feed ("/feed/you"): {"continuation"?}. The account's own shelves —
// history, playlists, Watch later, Liked videos — each with a View-all CTA. The engine owns the
// browseId/params pair (verified live 2026-07-30); callers gate on the sign-in state themselves
// (an anonymous browse answers a nudge page, not an error).
onAccept(
  "yt/library",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    const fresh = !putIfString(fields, args, "continuation");
    if (fresh) {
      fields["browseId"] = "FElibrary";
      fields["params"] = "KgN5b3U%3D";
    }
    const page = await browsePage(fields, hostLogger(args, "yt/library"));
    if (fresh) {
      // The design leads with the user's playlists; YouTube serves History first. The shelf is
      // recognized by its own-feed deeplink, never its localized title.
      const sections = page["sections"];
      for (let i = 1; i < sections.length; i++) {
        const section = sections[i];
        if (section === undefined) continue;
        const header = section["header"];
        if (header !== undefined && header["ctaDeeplink"] === "/feed/playlists") {
          const moved = sections.splice(i, 1)[0];
          if (moved !== undefined) sections.unshift(moved);
          break;
        }
      }
    }
    // Library shelves are overviews (each has its own full page behind the View-all/nav links) —
    // collapse them to a scan-friendly height: 5 rows single-column, 3 rows in any grid.
    for (const section of page["sections"]) {
      section["collapsible"] = true;
      section["collapsedRows"] = [5, 3];
    }
    await writePage(out, page);
  }),
);

// The Recommended feed's harvest — the WebView page's watch-next payload for the music video it is
// playing. Fire-and-forget from the app's side: it returns an empty object, never a page.
onAccept("yt/webview-next", harvestCommand);

// The Recommended feed itself: {} — read wholly off the cache, no network, no continuation.
onAccept(
  "yt/recommended",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    await writePage(out, await recommendedPage());
  }),
);

// Trending Music: {} — the region's charts, a single page (charts publish no continuation).
// The whole request lives on charts.youtube.com with its own client; see charts.ts.
onAccept(
  "yt/trending",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    await writePage(out, await trendingMusicPage(hostLogger(args, "yt/trending")));
  }),
);

// A playlist detail: {"url","continuation"?}, url an engine-composed "/playlist?list=…".
// Regular lists browse as VL<id>; Mixes/radios (RD…) aren't browsable — YouTube only materializes
// them on the watch page — so /next with (seed videoId, playlistId, start-radio params) returns the
// playlist panel, and only that section belongs on the detail page (the response also carries the
// watch page's related videos). An infinite panel pages through the engine's own mixToken.
onAccept(
  "yt/playlist",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const log = hostLogger(args, "yt/playlist");
    const continuation = asString(args["continuation"]);
    if (continuation !== undefined) {
      // Our fabricated Mix continuation (see mixToken): re-call /next around the token's video and
      // return only the items PAST it — the server slides a ~25-item window, it never appends.
      const mix = parseMixToken(continuation);
      if (mix !== null) {
        const windowPage = collectPage(
          await innertubeJson(
            "next",
            {
              "videoId": mix.videoId,
              "playlistId": mix.playlistId,
              "playlistIndex": mix.index,
            },
            log,
          ),
        );
        // The panel (when present) is always collectPage's first section. Bracket access only:
        // wire objects are quoted-key, dotted access renames apart under Closure ADVANCED.
        const firstSection = windowPage["sections"][0];
        const windowItems =
          windowPage["pageHeader"] !== undefined && firstSection !== undefined
            ? firstSection["items"]
            : [];
        let at = -1;
        for (let i = 0; i < windowItems.length; i++) {
          const it = windowItems[i];
          if (it !== undefined && it["id"] === mix.videoId) at = i;
        }
        const fresh = windowItems.slice(at + 1);
        const page: WireFeedPage = { "sections": [{ "layout": "list", "items": fresh }] };
        const last = fresh[fresh.length - 1];
        if (last !== undefined) {
          page["continuation"] = mixToken(mix.index + fresh.length, mix.playlistId, last["id"]);
        }
        await writePage(out, page);
        return;
      }
      await writePage(out, await browsePage({ "continuation": continuation }, log));
      return;
    }
    const url = asString(args["url"]);
    if (url === undefined) throw JsEngineErrorImpl.parse("yt/playlist needs url or continuation");
    const query = urlQuery(url);
    const list = query["list"];
    if (list === undefined) throw JsEngineErrorImpl.parse("yt/playlist url has no list: " + url);
    if (isMixPlaylist(list)) {
      const fields: Record<string, unknown> = { "playlistId": list, "params": "wAEB" };
      const seed = query["v"] ?? mixSeedVideoId(list);
      if (seed !== undefined) fields["videoId"] = seed;
      const page = collectPage(await innertubeJson("next", fields, log));
      page["sections"] = page["sections"].slice(0, 1);
      await writePage(out, page);
      return;
    }
    await writePage(
      out,
      await browsePage({ "browseId": list.indexOf("VL") === 0 ? list : "VL" + list }, log),
    );
  }),
);

// A channel detail: {"url","continuation"?}, url an engine-composed
// "/channel/<UC…>[?params=<tab>]" (item urls, tab urls, shelf CTAs all share the shape).
// The Shorts tab needs its own parsing mode (Shorts are skipped everywhere else), and the mode
// must survive paging — so its continuations are wrapped with an engine-private prefix.
const SHORTS_CONT_PREFIX = "ychshorts1:";

// The signed-in account's own feed pages, served through yt/channel: an app link composed here
// (deeplinkFor) or by the app's fixed library nav rows, decoded back to its browseId here.
const FEED_BROWSE_IDS: Record<string, string> = {
  "/feed/history": "FEhistory",
  "/feed/playlists": "FEplaylist_aggregation",
};

onAccept(
  "yt/channel",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    let shortsTab = false;
    // The app link is "/channel/<UC…>[/<tab-slug>][?params=…]" — the slug names the tab (see
    // channelUrl), so the Shorts mode is a path read, not a params decode.
    const channelPath = ((): { id: string; slug?: string } | undefined => {
      const url = asString(args["url"]);
      if (url === undefined) return undefined;
      const q = url.indexOf("?");
      const path = q < 0 ? url : url.slice(0, q);
      if (path.indexOf("/channel/") !== 0 || path.length <= "/channel/".length) return undefined;
      const rest = path.slice("/channel/".length);
      const cut = rest.indexOf("/");
      if (cut < 0) return { id: rest };
      const out: { id: string; slug?: string } = { id: rest.slice(0, cut) };
      const slug = rest.slice(cut + 1);
      if (slug !== "") out.slug = slug;
      return out;
    })();
    const continuation = asString(args["continuation"]);
    const sortParams = continuation !== undefined ? parseChannelSortToken(continuation) : null;
    if (sortParams !== null) {
      // A sort-menu chip (see channelSortToken): a fresh browse with the sort baked into params.
      // Only the Playlists tab ships a browse-typed sort menu today (Videos/Shorts sort via
      // continuation-typed chips, which keep the Shorts wrap) — so no Shorts mode here.
      if (channelPath === undefined)
        throw JsEngineErrorImpl.parse("yt/channel sort needs the channel url");
      fields["browseId"] = channelPath.id;
      fields["params"] = sortParams;
    } else if (continuation !== undefined) {
      if (continuation.indexOf(SHORTS_CONT_PREFIX) === 0) {
        shortsTab = true;
        fields["continuation"] = continuation.slice(SHORTS_CONT_PREFIX.length);
      } else {
        fields["continuation"] = continuation;
      }
    } else {
      const url = asString(args["url"]) ?? "";
      const q = url.indexOf("?");
      const feedId = FEED_BROWSE_IDS[q < 0 ? url : url.slice(0, q)];
      if (feedId !== undefined) {
        fields["browseId"] = feedId;
      } else {
        if (channelPath === undefined)
          throw JsEngineErrorImpl.parse("yt/channel needs a /channel/ url or continuation");
        fields["browseId"] = channelPath.id;
        const params = urlQuery(url)["params"];
        if (params !== undefined) fields["params"] = params;
        shortsTab = channelPath.slug === "shorts";
      }
    }
    const page = collectPage(
      await innertubeJson("browse", fields, hostLogger(args, "yt/channel")),
      {
        shorts: shortsTab,
      },
    );
    if (shortsTab) {
      // Paging AND the sort chips must stay in Shorts mode — wrap every token the app can send back.
      const cont = page["continuation"];
      if (cont !== undefined) page["continuation"] = SHORTS_CONT_PREFIX + cont;
      const chips = page["chips"];
      if (chips !== undefined) {
        for (const c of chips) {
          const token = c["token"];
          if (token !== undefined) c["token"] = SHORTS_CONT_PREFIX + token;
        }
      }
    }
    await writePage(out, page);
  }),
);

// {"query","params"?} or {"continuation"} — query and continuation are exclusive upstream.
onAccept(
  "yt/search",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    const continuation = asString(args["continuation"]);
    const chip = continuation !== undefined ? parseSearchChipToken(continuation) : null;
    if (chip !== null) {
      // A chip "token" is a wrapped fresh search (see searchChipToken), not a continuation.
      fields["query"] = chip.query;
      if (chip.params !== undefined) fields["params"] = chip.params;
    } else if (!putIfString(fields, args, "continuation")) {
      if (!putIfString(fields, args, "query"))
        throw JsEngineErrorImpl.parse("yt/search needs query or continuation");
      putIfString(fields, args, "params");
    }
    const page = collectPage(await innertubeJson("search", fields, hostLogger(args, "yt/search")));
    // Music-first ranking applies to the loose results runs only (headerless sections); the
    // artist card's sections and titled shelves keep their order.
    for (const s of page["sections"]) {
      if (s["header"] === undefined) s["items"] = rankSearchItems(s["items"]);
    }
    await writePage(out, page);
  }),
);

// Watch-next: {"videoId"} or {"continuation"}; the related list plus the autoplay target.
// (Mix playlist reads go through yt/playlist — this is the playback-queue surface.)
onAccept(
  "yt/next",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    if (!putIfString(fields, args, "continuation")) {
      if (!putIfString(fields, args, "videoId"))
        throw JsEngineErrorImpl.parse("yt/next needs videoId or continuation");
    }
    const root = await innertubeJson("next", fields, hostLogger(args, "yt/next"));
    const page = collectPage(root);
    // The end screen is the better related list — YouTube's own "watch next" picks, and it carries
    // the Mix/radio tiles the secondary column can miss. It sits outside collectPage's scanned
    // roots (playerOverlays) and largely repeats that column, so merge it in FIRST and drop the
    // repeats. This is the playback queue, so music leads: a video's related list is full of
    // reaction/commentary uploads that a music app should not autoplay ahead of the songs.
    const endScreen = collectItems(
      get(root, "playerOverlays", "playerOverlayRenderer", "endScreen"),
    );
    const first = page["sections"][0];
    const related = first !== undefined && first["header"] === undefined ? first["items"] : [];
    if (endScreen.length > 0 || related.length > 0) {
      const merged = rankSearchItems(dedupeItems(endScreen.concat(related)));
      if (first !== undefined && first["header"] === undefined) first["items"] = merged;
      else page["sections"].unshift({ "layout": "list", "items": merged });
    }
    const autoplay = asString(
      get(
        root,
        "contents",
        "twoColumnWatchNextResults",
        "autoplay",
        "autoplay",
        "sets",
        0,
        "autoplayVideo",
        "watchEndpoint",
        "videoId",
      ),
    );
    if (autoplay !== undefined) page["autoplayVideoId"] = autoplay;
    await writePage(out, page);
  }),
);

// Search autocomplete — the classic suggest service, not youtubei. client=firefox returns a bare
// JSON array ["q", [suggestions…]]; a JSONP wrapper (other clients) is stripped defensively.
onAccept(
  "yt/suggest",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const query = asString(args["query"]);
    if (query === undefined) throw JsEngineErrorImpl.parse("yt/suggest needs query");
    const locale = await effectiveLocale();
    const hl = locale.hl;
    const gl = locale.gl;
    const url =
      "https://suggestqueries-clients6.youtube.com/complete/search?client=firefox&ds=yt" +
      "&q=" +
      encodeURIComponent(query) +
      "&hl=" +
      encodeURIComponent(hl) +
      "&gl=" +
      encodeURIComponent(gl);
    const res = await HttpRequest.get(url).logger(hostLogger(args, "yt/suggest")).build().execute();
    if (!res.ok) {
      res.close();
      throw JsEngineErrorImpl.network("yt suggest HTTP " + res.status);
    }
    const text = await res.text();
    // JSONP wrapper (non-firefox clients) stripped by slicing, not regex: a "(" before any "["
    // means a callback wraps the payload — take first-"(" to last-")" (which must be followed by
    // whitespace only). A bare JSON array passes through untouched.
    let payload = text;
    const open = text.indexOf("(");
    const bracket = text.indexOf("[");
    if (open !== -1 && (bracket === -1 || open < bracket)) {
      const close = text.lastIndexOf(")");
      if (close > open && text.slice(close + 1).trim() === "")
        payload = text.slice(open + 1, close);
    }
    const arr = JSON.parse(payload) as unknown[];
    const suggestions: string[] = [];
    const list = arr[1];
    if (Array.isArray(list)) {
      for (const e of list) {
        if (typeof e === "string") suggestions.push(e);
        else if (Array.isArray(e) && typeof e[0] === "string") suggestions.push(e[0]);
      }
    }
    const result: WireSuggest = { "suggestions": suggestions };
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

// The Settings pickers' one read: {} -> {"hl","gl" (the pick, "" = automatic), "autoHl","autoGl"
// (what automatic resolves to), "languages","regions" (YouTube's menus, labelled in the effective
// language)}. The app asks when it opens the pickers; picking calls yt/locale/apply.
onAccept(
  "yt/locales",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    await writeLocales(out, await localeOptions());
  }),
);

// The locale-changed notification: {"gl"} (the region pick, "" = automatic) -> {}. hl is NOT an
// argument — the app language is the one hl, re-pulled from host settings here (the engine's
// cache invalidated first). PREF is rewritten (hl slot + the pick's gl slot — PREF itself is the
// pick's persistent record — s_gl deleted), the cached session is stamped with the new pair, and
// the session REFRESHES under the new identity: the re-parsed ytcfg GL lands as the working
// region value — YouTube outranks the pick (offline, the stale session is dropped instead; the
// next online call re-scrapes). An unchanged apply is a free no-op.
onAccept(
  "yt/locale/apply",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const gl = asString(args["gl"]) ?? "";
    invalidateAppLanguage();
    const changed = await applyLocaleCookie(gl);
    if (changed) {
      await overrideSessionLocale((await pulledAppLanguage()) ?? "", gl);
      try {
        await getSession(0);
      } catch (e) {
        await invalidateSession();
      }
    }
    const p = out.writeObjectAsJson({});
    if (p) await p;
  }),
);

async function writeLocales(out: PipeSink, locales: WireLocales): Promise<void> {
  const p = out.writeObjectAsJson(locales);
  if (p) await p;
}
