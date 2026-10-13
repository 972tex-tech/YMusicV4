// The Recommended feed — legacy YMusicRecommendedFeedMod's job, wholly inside the bundle.
//
// It is not a recommender: it is YouTube's OWN related-videos list for the music the user actually
// played, kept locally and replayed as a feed. Everything is offline — reading it makes no network
// call at all, so the tab works on a plane.
//
// Harvest: while a music video plays, the MSE player reports its watch-next payload (music-category
// videos only); the app forwards it verbatim and this parses + stores it.
// One cache entry per SOURCE video, so replaying a song refreshes its entry instead of consuming a
// slot, and the scope's keepCount evictor drops the oldest play — the ring buffer legacy hand-rolled
// over a key-value store, for free.
//
// Read: union every stored play, dedupe, shuffle. The shuffle is per read on purpose — the feed
// stays alive between plays. No continuation: what's on disk IS the feed.

import { commandHandler } from "../../protocol/command";
import { keepCount, scopedCache } from "../../protocol/cache";
import { collectItems, dedupeItems } from "./parse";
import { asString, get } from "./scan";
import type { WireFeedItem, WireFeedPage } from "./wire";

/** Cache partition: its own keys, listing and eviction (see scopedCache). */
const SCOPE = "yt.recommended";

/** How many plays back the feed draws from (legacy MAX_DATA). */
const KEEP_PLAYS = 10;

function store(): Promise<Awaited<ReturnType<typeof scopedCache>>> {
  return scopedCache(SCOPE, keepCount(KEEP_PLAYS));
}

/**
 * Store the related items of one play. `contents` is the watch-next results array as the page saw
 * it — either generation parses (secondaryResults' lockupViewModels, or the embedded player's
 * endScreen renderers). The played video itself never enters its own recommendations.
 */
export async function harvestWatchNext(videoId: string, contents: unknown): Promise<void> {
  const items: WireFeedItem[] = [];
  // The page sends the end screen AND the secondary column, which overlap — dedupe here so the
  // stored entry is clean, not just the merged read.
  for (const item of dedupeItems(collectItems(contents))) {
    if (item["id"] !== videoId) items.push(item);
  }
  if (items.length === 0) return;
  await (await store()).setJson(videoId, { "items": items });
}

/** Fisher-Yates over `items` in place. */
function shuffle(items: WireFeedItem[]): void {
  for (let i = items.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    const tmp = items[i]!;
    items[i] = items[j]!;
    items[j] = tmp;
  }
}

/**
 * The feed: every harvested play, deduped by (kind, id) and shuffled. An empty result is a normal
 * cold start — nothing has been played yet — not an error; the app shows its own invitation to go
 * play something.
 */
export async function recommendedPage(): Promise<WireFeedPage> {
  const cache = await store();
  const seen: Record<string, true> = {};
  const items: WireFeedItem[] = [];
  for (const entry of await cache.list()) {
    const stored = await cache.getJson(entry.key);
    const list = get(stored, "items");
    if (!Array.isArray(list)) continue;
    for (const raw of list) {
      const id = asString(get(raw, "id"));
      const kind = asString(get(raw, "kind"));
      if (id === undefined || kind === undefined) continue;
      const dedupe = kind + ":" + id;
      if (seen[dedupe] === true) continue;
      seen[dedupe] = true;
      items.push(raw as WireFeedItem);
    }
  }
  if (items.length === 0) return { "sections": [] };
  shuffle(items);
  return { "sections": [{ "layout": "list", "items": items }] };
}

/** The harvest command: {"videoId","contents"} — `contents` is the payload's JSON text, opaque to
 *  the app (it forwards what the page sent, it never parses it). */
export const harvestCommand = commandHandler(async (src, out) => {
  const args = await src.readJsonObject();
  const videoId = asString(args["videoId"]);
  const contents = asString(args["contents"]);
  // A payload the page couldn't produce is not an error worth failing playback over.
  if (videoId !== undefined && contents !== undefined) {
    await harvestWatchNext(videoId, JSON.parse(contents));
  }
  const p = out.writeObjectAsJson({});
  if (p) await p;
});
