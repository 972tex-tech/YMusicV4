// Pure parsers for the watch page minus the player — the /next video metadata + description, and
// comment / reply pages (entity-backed commentViewModels). Commands: videoDetails.ts.

import { channelUrl } from "./parse";
import { asString, get, isObj, textOf, thumbsOf, walk } from "./scan";
import type { WireChip, WireComment, WireCommentPage, WireFactoid, WireVideoDetails } from "./wire";

/** The token a continuation item (an auto-trigger or a "Show more" button) carries. */
function continuationToken(item: unknown): string | undefined {
  return (
    asString(get(item, "continuationEndpoint", "continuationCommand", "token")) ??
    asString(get(item, "button", "buttonRenderer", "command", "continuationCommand", "token"))
  );
}

function factoidOf(node: unknown): WireFactoid | undefined {
  const f =
    get(node, "factoidRenderer") ??
    get(node, "viewCountFactoidRenderer", "factoid", "factoidRenderer");
  const value = textOf(get(f, "value"));
  const label = textOf(get(f, "label"));
  return value !== undefined && label !== undefined
    ? { "value": value, "label": label }
    : undefined;
}

function setOwner(out: WireVideoDetails, owner: unknown): void {
  if (!isObj(owner)) return;
  const name = textOf(owner["title"]);
  if (name !== undefined) out["channelTitle"] = name;
  const id = asString(get(owner, "navigationEndpoint", "browseEndpoint", "browseId"));
  if (id !== undefined) out["channelUrl"] = channelUrl(id);
  const thumbs = thumbsOf(owner["thumbnail"]);
  if (thumbs !== undefined) out["channelThumbs"] = thumbs;
  const subscribers = textOf(owner["subscriberCountText"]);
  if (subscribers !== undefined) out["subscribers"] = subscribers;
}

export function videoDetailsOf(root: Record<string, unknown>): WireVideoDetails {
  const out: WireVideoDetails = {};
  walk(root, (key, value) => {
    if (!isObj(value)) return;
    if (key === "videoPrimaryInfoRenderer") {
      const title = textOf(value["title"]);
      if (title !== undefined) out["title"] = title;
      return true;
    }
    if (key === "videoDescriptionHeaderRenderer") {
      const facts = value["factoid"];
      if (Array.isArray(facts)) {
        const list: WireFactoid[] = [];
        for (const f of facts) {
          const factoid = factoidOf(f);
          if (factoid !== undefined) list.push(factoid);
        }
        if (list.length > 0) out["factoids"] = list;
      }
      return true;
    }
    if (key === "expandableVideoDescriptionBodyRenderer") {
      const body = textOf(value["attributedDescriptionBodyText"]);
      if (body !== undefined) out["description"] = body;
      return true;
    }
    if (key === "videoSecondaryInfoRenderer") {
      setOwner(out, get(value, "owner", "videoOwnerRenderer"));
      if (out["description"] === undefined) {
        const body = textOf(value["attributedDescription"]);
        if (body !== undefined) out["description"] = body;
      }
      return true;
    }
    if (key === "itemSectionRenderer" && value["sectionIdentifier"] === "comment-item-section") {
      const contents = value["contents"];
      if (Array.isArray(contents)) {
        for (const c of contents) {
          const token = continuationToken(get(c, "continuationItemRenderer"));
          if (token !== undefined) out["commentsContinuation"] = token;
        }
      }
      return true;
    }
    if (
      key === "engagementPanelSectionListRenderer" &&
      value["panelIdentifier"] === "engagement-panel-comments-section"
    ) {
      const count = textOf(
        get(value, "header", "engagementPanelTitleHeaderRenderer", "contextualInfo"),
      );
      if (count !== undefined) out["commentsCount"] = count;
      return true;
    }
    return;
  });
  return out;
}

// Comment text and author live in frameworkUpdates entities, keyed from each commentViewModel.
function entitiesOf(root: Record<string, unknown>): Record<string, Record<string, unknown>> {
  const out: Record<string, Record<string, unknown>> = {};
  const mutations = get(root, "frameworkUpdates", "entityBatchUpdate", "mutations");
  if (!Array.isArray(mutations)) return out;
  for (const m of mutations) {
    const key = asString(get(m, "entityKey"));
    const payload = get(m, "payload");
    if (key !== undefined && isObj(payload)) out[key] = payload;
  }
  return out;
}

function commentOf(
  vm: unknown,
  entities: Record<string, Record<string, unknown>>,
): WireComment | undefined {
  const commentKey = asString(get(vm, "commentKey"));
  const entity = commentKey !== undefined ? entities[commentKey] : undefined;
  const payload = get(entity, "commentEntityPayload");
  const id = asString(get(payload, "properties", "commentId")) ?? asString(get(vm, "commentId"));
  const text = asString(get(payload, "properties", "content", "content"));
  if (id === undefined || text === undefined) return undefined;
  const comment: WireComment = { "id": id, "text": text };
  const author = get(payload, "author");
  const name = asString(get(author, "displayName"));
  if (name !== undefined) comment["author"] = name;
  const avatar = asString(get(author, "avatarThumbnailUrl"));
  if (avatar !== undefined) comment["authorThumbs"] = [{ "url": avatar }];
  const authorId = asString(get(author, "channelId"));
  if (authorId !== undefined) comment["authorChannelUrl"] = channelUrl(authorId);
  if (get(author, "isCreator") === true) comment["byCreator"] = true;
  if (get(author, "isVerified") === true) comment["verified"] = true;
  const published = asString(get(payload, "properties", "publishedTime"));
  if (published !== undefined) comment["published"] = published;
  const likes = asString(get(payload, "toolbar", "likeCountNotliked"));
  if (likes !== undefined && likes !== "" && likes !== "0") comment["likes"] = likes;
  const replies = asString(get(payload, "toolbar", "replyCount"));
  if (replies !== undefined && replies !== "" && replies !== "0") comment["replyCount"] = replies;
  const pinned = textOf(get(vm, "pinnedText"));
  if (pinned !== undefined && pinned !== "") comment["pinned"] = pinned;
  const stateKey = asString(get(vm, "toolbarStateKey"));
  const state = stateKey !== undefined ? entities[stateKey] : undefined;
  const heart = asString(get(state, "engagementToolbarStateEntityPayload", "heartState"));
  if (heart === "TOOLBAR_HEART_STATE_HEARTED") comment["hearted"] = true;
  return comment;
}

export function commentPageOf(root: Record<string, unknown>): WireCommentPage {
  const entities = entitiesOf(root);
  const page: WireCommentPage = { "comments": [] };
  walk(root, (key, value) => {
    if (!isObj(value)) return;
    if (key === "frameworkUpdates") return true;
    if (key === "commentsHeaderRenderer") {
      const count = textOf(value["countText"]);
      if (count !== undefined) page["countText"] = count;
      const items = get(value, "sortMenu", "sortFilterSubMenuRenderer", "subMenuItems");
      if (Array.isArray(items)) {
        const sort: WireChip[] = [];
        for (const it of items) {
          const title = asString(get(it, "title"));
          if (title === undefined) continue;
          const chip: WireChip = { "title": title };
          const token = asString(get(it, "serviceEndpoint", "continuationCommand", "token"));
          if (token !== undefined) chip["token"] = token;
          if (get(it, "selected") === true) chip["selected"] = true;
          sort.push(chip);
        }
        if (sort.length > 0) page["sort"] = sort;
      }
      const box = get(value, "createRenderer", "commentSimpleboxRenderer");
      const placeholder = textOf(get(box, "placeholderText"));
      if (placeholder !== undefined) page["composerPlaceholder"] = placeholder;
      const create = asString(
        get(
          box,
          "submitButton",
          "buttonRenderer",
          "serviceEndpoint",
          "createCommentEndpoint",
          "createCommentParams",
        ),
      );
      if (create !== undefined) page["createToken"] = create;
      return true;
    }
    if (key === "commentThreadRenderer") {
      const comment = commentOf(get(value, "commentViewModel", "commentViewModel"), entities);
      if (comment !== undefined) {
        const more = get(value, "replies", "commentRepliesRenderer", "contents");
        if (Array.isArray(more)) {
          for (const c of more) {
            const token = continuationToken(get(c, "continuationItemRenderer"));
            if (token !== undefined) comment["repliesContinuation"] = token;
          }
        }
        page["comments"].push(comment);
      }
      return true;
    }
    if (key === "commentViewModel") {
      const comment = commentOf(value, entities);
      if (comment !== undefined) page["comments"].push(comment);
      return true;
    }
    if (key === "continuationItemRenderer") {
      const token = continuationToken(value);
      if (token !== undefined) page["continuation"] = token;
      return true;
    }
    return;
  });
  return page;
}
