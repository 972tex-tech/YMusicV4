// The viewer's own YouTube playlists (signed in): the add-to-playlist picker (yt/playlist/targets),
// adding a video (yt/playlist/add), creating one from videos (yt/playlist/create), and saving an
// owned playlist's sort order (setPlaylistOrder, yt/playlist's Sort menu).
// Kotlin twin: yt/YtPlaylistEdit.kt.

import { onAccept } from "../../transport";
import { JsEngineErrorImpl, commandHandler } from "../../protocol/command";
import { hostLogger } from "../../protocol/logger";
import { innertubeJson } from "./innertube";
import type { JsLogger } from "../../protocol/logger";
import { REMOVE_TOKEN_PREFIX } from "./parse";
import { asString, get, isObj, textOf, walk } from "./scan";
import type { WirePlaylistTarget } from "./wire";

async function writeJson(out: PipeSink, value: object): Promise<void> {
  const p = out.writeObjectAsJson(value as Record<string, unknown>);
  if (p) await p;
}

function requiredString(args: Record<string, unknown>, name: string, command: string): string {
  const v = asString(args[name]);
  if (v === undefined || v === "") throw JsEngineErrorImpl.parse(command + " needs " + name);
  return v;
}

function videoIdsOf(args: Record<string, unknown>, command: string): string[] {
  const raw = args["videoIds"];
  const ids: string[] = [];
  if (Array.isArray(raw)) {
    for (const v of raw) {
      const id = asString(v);
      if (id !== undefined && id !== "") ids.push(id);
    }
  }
  if (ids.length === 0) throw JsEngineErrorImpl.parse(command + " needs videoIds");
  return ids;
}

// {"videoId"} -> {"playlists": WirePlaylistTarget[]}: the viewer's playlists (Watch later included),
// each flagged when it already holds the video.
onAccept(
  "yt/playlist/targets",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const videoId = requiredString(args, "videoId", "yt/playlist/targets");
    const root = await innertubeJson(
      "playlist/get_add_to_playlist",
      { "videoIds": [videoId], "excludeWatchLater": false },
      hostLogger(args, "yt/playlist/targets"),
    );
    const playlists: WirePlaylistTarget[] = [];
    walk(root, (k, v) => {
      if (k !== "playlistAddToOptionRenderer" || !isObj(v)) return;
      const playlistId = asString(v["playlistId"]);
      const title = textOf(v["title"]);
      if (playlistId === undefined || title === undefined) return true;
      const target: WirePlaylistTarget = { "playlistId": playlistId, "title": title };
      const privacy = asString(v["privacy"]);
      if (privacy !== undefined) target["privacy"] = privacy;
      if (v["containsSelectedVideos"] === "ALL") target["containsVideo"] = true;
      playlists.push(target);
      return true;
    });
    await writeJson(out, { "playlists": playlists });
  }),
);

/** edit_playlist / create answer HTTP 200 with a failure status in the body. */
function requireSucceeded(root: Record<string, unknown>, what: string, code: string): void {
  const status = asString(root["status"]);
  if (status !== undefined && status !== "STATUS_SUCCEEDED") {
    throw new JsEngineErrorImpl(what + " answered " + status, code);
  }
}

// {"playlistId","videoId"} -> {}.
onAccept(
  "yt/playlist/add",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const playlistId = requiredString(args, "playlistId", "yt/playlist/add");
    const videoId = requiredString(args, "videoId", "yt/playlist/add");
    const root = await innertubeJson(
      "browse/edit_playlist",
      {
        "playlistId": playlistId,
        "actions": [{ "action": "ACTION_ADD_VIDEO", "addedVideoId": videoId }],
      },
      hostLogger(args, "yt/playlist/add"),
    );
    requireSucceeded(root, "edit_playlist", "E20105");
    await writeJson(out, {});
  }),
);

// {"token"} -> {}. `token` is a video item's removeToken.
onAccept(
  "yt/playlist/remove",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const token = requiredString(args, "token", "yt/playlist/remove");
    const body =
      token.indexOf(REMOVE_TOKEN_PREFIX) === 0 ? token.slice(REMOVE_TOKEN_PREFIX.length) : "";
    const cut = body.indexOf(":");
    if (cut <= 0 || cut === body.length - 1) {
      throw JsEngineErrorImpl.parse("yt/playlist/remove bad token " + token);
    }
    const root = await innertubeJson(
      "browse/edit_playlist",
      {
        "playlistId": body.slice(0, cut),
        "actions": [{ "action": "ACTION_REMOVE_VIDEO", "setVideoId": body.slice(cut + 1) }],
      },
      hostLogger(args, "yt/playlist/remove"),
    );
    requireSucceeded(root, "edit_playlist", "E20111");
    await writeJson(out, {});
  }),
);

// {"title","privacy","videoIds"} -> {"playlistId"}. `privacy` is PUBLIC | UNLISTED | PRIVATE.
onAccept(
  "yt/playlist/create",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const title = requiredString(args, "title", "yt/playlist/create");
    const privacy = requiredString(args, "privacy", "yt/playlist/create");
    const videoIds = videoIdsOf(args, "yt/playlist/create");
    const root = await innertubeJson(
      "playlist/create",
      { "title": title, "privacyStatus": privacy, "videoIds": videoIds },
      hostLogger(args, "yt/playlist/create"),
    );
    requireSucceeded(root, "playlist/create", "E20106");
    const playlistId =
      asString(root["playlistId"]) ?? asString(get(root, "playlist", "playlistId"));
    if (playlistId === undefined) {
      throw new JsEngineErrorImpl("playlist/create answered without a playlistId", "E20107");
    }
    await writeJson(out, { "playlistId": playlistId });
  }),
);

/** Save [playlistId]'s video order — YouTube's Sort menu on an owned playlist does exactly this. */
export async function setPlaylistOrder(
  playlistId: string,
  order: number,
  params: string,
  log?: JsLogger,
): Promise<void> {
  const fields: Record<string, unknown> = {
    "playlistId": playlistId,
    "actions": [{ "action": "ACTION_SET_PLAYLIST_VIDEO_ORDER", "playlistVideoOrder": order }],
  };
  if (params !== "") fields["params"] = params;
  const root = await innertubeJson("browse/edit_playlist", fields, log);
  requireSucceeded(root, "edit_playlist", "E20160");
}
