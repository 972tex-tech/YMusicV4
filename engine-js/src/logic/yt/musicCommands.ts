// The music.youtube.com (WEB_REMIX) commands the app calls (Kotlin twin: yt/YtMusicCommands.kt).
// Same command-in / page-out contract as commands.ts, but the WEB_REMIX client (ytmusic.ts) and the
// music renderer walker (musicParse.ts). Registered from logic/index.ts. Streams are NOT served here
// — a song's videoId is handed to the existing WEB player path; this surface is catalog only.

import { onAccept } from "../../transport";
import { JsEngineErrorImpl, commandHandler } from "../../protocol/command";
import { musicJson } from "./ytmusic";
import { getSession } from "./session";
import { hostLogger } from "../../protocol/logger";
import type { JsLogger } from "../../protocol/logger";
import { collectMusicPage, parseMusicChipToken, parseMusicLibraryChipToken } from "./musicParse";
import { parseSearchChipToken } from "./parse";
import { asString, get, isObj, textOf, walk } from "./scan";
import type { WireFeedPage, WireSuggest } from "./wire";

async function writePage(out: PipeSink, page: WireFeedPage): Promise<void> {
  const p = out.writeObjectAsJson(page);
  if (p) await p;
}

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

/** The decoded query of an app link ("/ytmusic/browse?id=…"); {} when there is none. */
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

async function musicBrowsePage(
  fields: Record<string, unknown>,
  log?: JsLogger,
): Promise<WireFeedPage> {
  return collectMusicPage(await musicJson("browse", fields, log));
}

/**
 * The browse fields for a music feed command (home/explore). A mood/genre chip token re-browses the
 * feed's own [browseId] with the chip's params; a real continuation pages the current feed; otherwise
 * it's the fresh feed. The engine owns the browseId either way (the app only round-trips the chip).
 */
function musicFeedFields(args: Record<string, unknown>, browseId: string): Record<string, unknown> {
  const fields: Record<string, unknown> = {};
  const continuation = asString(args["continuation"]);
  const chipParams = continuation !== undefined ? parseMusicChipToken(continuation) : null;
  if (chipParams !== null) {
    fields["browseId"] = browseId;
    fields["params"] = chipParams;
  } else if (!putIfString(fields, args, "continuation")) {
    fields["browseId"] = browseId;
  }
  return fields;
}

// Music Home: {"continuation"?}. The engine owns the browseId (FEmusic_home) — the region's music
// home (regional charts / start-radio at a cold anonymous start; personalized once warmed). The
// mood/genre chip row re-browses FEmusic_home with the chip's params.
onAccept(
  "ytmusic/home",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    // A user reload: re-scrape the session before browsing.
    if (args["refresh"] === true && args["continuation"] == null) await getSession(0);
    await writePage(
      out,
      await musicBrowsePage(
        musicFeedFields(args, "FEmusic_home"),
        hostLogger(args, "ytmusic/home"),
      ),
    );
  }),
);

// Music Explore: {"continuation"?}. browseId FEmusic_explore (new releases / moods & genres / charts).
onAccept(
  "ytmusic/explore",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    await writePage(
      out,
      await musicBrowsePage(
        musicFeedFields(args, "FEmusic_explore"),
        hostLogger(args, "ytmusic/explore"),
      ),
    );
  }),
);

// Music New releases: {"continuation"?}. browseId FEmusic_new_releases (new albums & singles).
onAccept(
  "ytmusic/new-releases",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    await writePage(
      out,
      await musicBrowsePage(
        musicFeedFields(args, "FEmusic_new_releases"),
        hostLogger(args, "ytmusic/new-releases"),
      ),
    );
  }),
);

// Music Charts: {"continuation"?}. browseId FEmusic_charts — the region's charts (region from the
// context gl; any country-picker chips ride the shared chip path).
onAccept(
  "ytmusic/charts",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    await writePage(
      out,
      await musicBrowsePage(
        musicFeedFields(args, "FEmusic_charts"),
        hostLogger(args, "ytmusic/charts"),
      ),
    );
  }),
);

// Music Library (needs sign-in): {"continuation"?}. Fresh = FEmusic_library_landing (every filter
// off — the user's playlists, artists and profiles mixed). A filter chip's token re-browses its
// SIBLING library feed (FEmusic_liked_playlists / _liked_albums / … — the chip carries the browseId,
// see musicParse filterChip); scroll paging AND the sort menu both ride real server continuations.
// The chip row wraps instead of scrolling (chipsWrap) — a fixed, short filter set sharing its line
// with the sort trigger, unlike the open-ended scrolling chip strips elsewhere.
onAccept(
  "ytmusic/library",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    const continuation = asString(args["continuation"]);
    const chipBrowseId =
      continuation !== undefined ? parseMusicLibraryChipToken(continuation) : null;
    if (chipBrowseId !== null) fields["browseId"] = chipBrowseId;
    else if (continuation !== undefined) fields["continuation"] = continuation;
    else fields["browseId"] = "FEmusic_library_landing";
    const page = await musicBrowsePage(fields, hostLogger(args, "ytmusic/library"));
    page["chipsWrap"] = true;
    await writePage(out, page);
  }),
);

// A music detail page: {"url","continuation"?}, url an engine-composed "/ytmusic/browse?id=<browseId>"
// (an album MPREb…, artist UC…/MPLA…, or playlist VL…). The browseId is round-tripped through the url;
// paging rides the server continuation.
onAccept(
  "ytmusic/browse",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    if (!putIfString(fields, args, "continuation")) {
      const url = asString(args["url"]);
      if (url === undefined)
        throw JsEngineErrorImpl.parse("ytmusic/browse needs url or continuation");
      const query = urlQuery(url);
      const id = query["id"];
      if (id === undefined) throw JsEngineErrorImpl.parse("ytmusic/browse url has no id: " + url);
      fields["browseId"] = id;
      // Charts / a mood-genre category need their params alongside the browseId.
      if (query["params"] !== undefined) fields["params"] = query["params"];
    }
    await writePage(out, await musicBrowsePage(fields, hostLogger(args, "ytmusic/browse")));
  }),
);

// Music search: {"query","params"?} or {"continuation"}. params filters to a result type
// (Songs/Albums/Artists/Playlists). The top-result card + typed shelves come back as sections.
onAccept(
  "ytmusic/search",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const fields: Record<string, unknown> = {};
    const continuation = asString(args["continuation"]);
    // A filter chip's "token" is a wrapped fresh search (query + the filter's params), not a server
    // continuation — YouTube Music has no filter dialog, only these chips (see musicParse searchChip).
    const chip = continuation !== undefined ? parseSearchChipToken(continuation) : null;
    if (chip !== null) {
      fields["query"] = chip.query;
      if (chip.params !== undefined) fields["params"] = chip.params;
    } else if (!putIfString(fields, args, "continuation")) {
      if (!putIfString(fields, args, "query"))
        throw JsEngineErrorImpl.parse("ytmusic/search needs query or continuation");
      putIfString(fields, args, "params");
    }
    await writePage(
      out,
      collectMusicPage(await musicJson("search", fields, hostLogger(args, "ytmusic/search"))),
    );
  }),
);

// Music autocomplete: {"query"} -> {"suggestions":[…]}. The richer music endpoint
// (music/get_search_suggestions) — query suggestions plus entity suggestions, flattened to the query
// strings for the search box.
onAccept(
  "ytmusic/suggest",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const query = asString(args["query"]);
    if (query === undefined) throw JsEngineErrorImpl.parse("ytmusic/suggest needs query");
    const root = await musicJson(
      "music/get_search_suggestions",
      { "input": query },
      hostLogger(args, "ytmusic/suggest"),
    );
    const suggestions: string[] = [];
    const seen: Record<string, true> = {};
    walk(root, (key, value) => {
      if (key !== "searchSuggestionRenderer" || !isObj(value)) return;
      const text =
        textOf(get(value, "suggestion")) ??
        asString(get(value, "navigationEndpoint", "searchEndpoint", "query"));
      if (text !== undefined && text !== "" && seen[text] !== true) {
        seen[text] = true;
        suggestions.push(text);
      }
      return true;
    });
    const result: WireSuggest = { "suggestions": suggestions };
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);
