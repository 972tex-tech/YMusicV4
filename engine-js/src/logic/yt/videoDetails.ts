// The watch page minus the player: video metadata + description (yt/video-details), comments and
// replies (yt/comments), posting a comment (yt/comment/create). Kotlin twin: yt/YtVideoDetails.kt.

import { onAccept } from "../../transport";
import { JsEngineErrorImpl, commandHandler } from "../../protocol/command";
import { hostLogger } from "../../protocol/logger";
import { innertubeJson } from "./innertube";
import { asString } from "./scan";
import { commentPageOf, videoDetailsOf } from "./watchParse";

async function writeJson(out: PipeSink, value: object): Promise<void> {
  const p = out.writeObjectAsJson(value as Record<string, unknown>);
  if (p) await p;
}

function requiredString(args: Record<string, unknown>, name: string, command: string): string {
  const v = asString(args[name]);
  if (v === undefined || v === "") throw JsEngineErrorImpl.parse(command + " needs " + name);
  return v;
}

// {"videoId"} -> WireVideoDetails.
onAccept(
  "yt/video-details",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const videoId = requiredString(args, "videoId", "yt/video-details");
    const root = await innertubeJson(
      "next",
      { "videoId": videoId },
      hostLogger(args, "yt/video-details"),
    );
    await writeJson(out, videoDetailsOf(root));
  }),
);

// {"continuation"} -> WireCommentPage: a video's comments (its details' commentsContinuation, a
// sort chip's token, or a page's continuation) or a thread's replies (repliesContinuation).
onAccept(
  "yt/comments",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const continuation = requiredString(args, "continuation", "yt/comments");
    const root = await innertubeJson(
      "next",
      { "continuation": continuation },
      hostLogger(args, "yt/comments"),
    );
    await writeJson(out, commentPageOf(root));
  }),
);

// The rating endpoint by the viewer's new status.
const RATE_ENDPOINTS: Record<string, string> = {
  "LIKE": "like/like",
  "DISLIKE": "like/dislike",
  "INDIFFERENT": "like/removelike",
};

// {"videoId","status","token"} -> {}. `status` is the viewer's new rating (LIKE | DISLIKE |
// INDIFFERENT); `token` is the details' rateTokens entry for it.
onAccept(
  "yt/video/rate",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const videoId = requiredString(args, "videoId", "yt/video/rate");
    const status = requiredString(args, "status", "yt/video/rate");
    const token = requiredString(args, "token", "yt/video/rate");
    const endpoint = RATE_ENDPOINTS[status];
    if (endpoint === undefined) throw JsEngineErrorImpl.parse("yt/video/rate status " + status);
    await innertubeJson(
      endpoint,
      { "target": { "videoId": videoId }, "params": token },
      hostLogger(args, "yt/video/rate"),
    );
    await writeJson(out, {});
  }),
);

// {"token","text"} -> the posted WireComment. `token` is a page's createToken.
onAccept(
  "yt/comment/create",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const token = requiredString(args, "token", "yt/comment/create");
    const text = requiredString(args, "text", "yt/comment/create");
    const root = await innertubeJson(
      "comment/create_comment",
      { "createCommentParams": token, "commentText": text },
      hostLogger(args, "yt/comment/create"),
    );
    const posted = commentPageOf(root)["comments"][0];
    if (posted === undefined) {
      throw new JsEngineErrorImpl("create_comment answered without the posted comment", "E20093");
    }
    await writeJson(out, posted);
  }),
);

// {"token"} -> {}. `token` is a comment's likeToken / unlikeToken.
onAccept(
  "yt/comment/action",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const token = requiredString(args, "token", "yt/comment/action");
    await innertubeJson(
      "comment/perform_comment_action",
      { "actions": [token] },
      hostLogger(args, "yt/comment/action"),
    );
    await writeJson(out, {});
  }),
);

// {"token","text"} -> the posted reply. `token` is the parent comment's replyToken.
onAccept(
  "yt/comment/reply",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const token = requiredString(args, "token", "yt/comment/reply");
    const text = requiredString(args, "text", "yt/comment/reply");
    const root = await innertubeJson(
      "comment/create_comment_reply",
      { "createReplyParams": token, "commentText": text },
      hostLogger(args, "yt/comment/reply"),
    );
    const posted = commentPageOf(root)["comments"][0];
    if (posted === undefined) {
      throw new JsEngineErrorImpl(
        "create_comment_reply answered without the posted reply",
        "E20098",
      );
    }
    await writeJson(out, posted);
  }),
);
