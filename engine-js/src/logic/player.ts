// App-facing player commands. init warms the singleton WEB player; load stamps the video id and
// calls loadVideoById (returns immediately). Host observes:
//   listen/player/status  — event groups: progress | state | picture (webview-js split)
//   listen/player/catalog — qualities
// play/pause/seek/stop are playVideo/pauseVideo/seekTo.

import { onAccept } from "../transport";
import { commandHandler, JsEngineErrorImpl } from "../protocol/command";
import {
  initPlayer,
  loadPlayer,
  mintPlayerPo,
  preloadPlayer,
  pausePlayer,
  playPlayer,
  seekPlayer,
  setPlayerLoop,
  setPlayerQuality,
  setPlayerViewport,
  statusPlayer,
  stopPlayer,
} from "./player/boot";
import { applyViewport } from "./player/viewport";
import { runCatalogSession, listenPlayerStatus } from "./player/push";
import { listCaptionTracks, runCaptionSession, setCaptionVss } from "./player/captions";
import { downloadWaitMs, peekDownloadJson, waitDownloadJson } from "./player/player_res";

onAccept(
  "player/init",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    applyViewport(a["width"], a["height"]);
    const result = await initPlayer();
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/load",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw new JsEngineErrorImpl("player/load missing videoId", "E20072");
    applyViewport(a["width"], a["height"]);
    const quality = a["quality"] != null ? String(a["quality"]) : undefined;
    const audioOnly = a["audioOnly"] === true;
    const result = await loadPlayer(id, quality, audioOnly);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/preload",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw new JsEngineErrorImpl("player/preload missing videoId", "E20091");
    const result = await preloadPlayer(id);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/setViewport",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const result = setPlayerViewport(a["width"], a["height"]);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/mintPo",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw JsEngineErrorImpl.parse("player/mintPo missing videoId");
    const result = await mintPlayerPo(id);
    const p = out.writeObjectAsJson(result);
    if (p) await p;
  }),
);

onAccept(
  "player/setLoop",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    setPlayerLoop(a["enable"] === true);
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/play",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    playPlayer();
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/pause",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    pausePlayer();
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/seek",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const ms = Number(a["positionMs"]);
    if (!Number.isFinite(ms) || ms < 0)
      throw new JsEngineErrorImpl("player/seek bad positionMs", "E20073");
    seekPlayer(ms / 1000);
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/stop",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    stopPlayer();
    const p = out.writeObjectAsJson({ "ok": true });
    if (p) await p;
  }),
);

onAccept(
  "player/setQuality",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const quality = String(a["quality"] ?? "");
    const audioOnly = a["audioOnly"] === true;
    setPlayerQuality(quality, audioOnly);
    const p = out.writeObjectAsJson({ "ok": true, "quality": quality, "audioOnly": audioOnly });
    if (p) await p;
  }),
);

onAccept(
  "player/status",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    const p = out.writeObjectAsJson(statusPlayer());
    if (p) await p;
  }),
);

onAccept("listen/player/status", async (pipe) => {
  try {
    await listenPlayerStatus(pipe);
    const pr = pipe.sink.close();
    if (pr) await pr;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (!/^EPIPE\b/.test(msg)) {
      try {
        const code = e instanceof JsEngineErrorImpl ? e.code : "E20001";
        const pr2 = pipe.sink.close(JSON.stringify({ "code": code, "message": msg }));
        if (pr2) await pr2;
      } catch (_e) {}
    }
  } finally {
    pipe.source.close();
  }
});

onAccept("listen/player/catalog", async (pipe) => {
  try {
    await runCatalogSession(pipe);
    const pr = pipe.sink.close();
    if (pr) await pr;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (!/^EPIPE\b/.test(msg)) {
      try {
        const code = e instanceof JsEngineErrorImpl ? e.code : "E20001";
        const pr2 = pipe.sink.close(JSON.stringify({ "code": code, "message": msg }));
        if (pr2) await pr2;
      } catch (_e) {}
    }
  } finally {
    pipe.source.close();
  }
});

onAccept(
  "player/captions",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    const p = out.writeObjectAsJson(listCaptionTracks());
    if (p) await p;
  }),
);

onAccept(
  "player/setCaption",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const vss = a["vssId"] == null || a["vssId"] === "" ? null : String(a["vssId"]);
    setCaptionVss(vss);
    const p = out.writeObjectAsJson({ "ok": true, "vssId": vss });
    if (p) await p;
  }),
);

onAccept(
  "player/playerResponse",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const id = String(a["videoId"] ?? "");
    if (!id) throw new JsEngineErrorImpl("player/playerResponse missing videoId", "E20087");
    let rec = peekDownloadJson(id);
    if (!rec) {
      await preloadPlayer(id);
      try {
        rec = await waitDownloadJson(id, downloadWaitMs());
      } catch (_e) {
        throw new JsEngineErrorImpl("player response not cached", "E20087");
      }
    }
    const p = out.writeObjectAsJson(rec);
    if (p) await p;
  }),
);

onAccept("player/caption", async (pipe) => {
  try {
    await runCaptionSession(pipe);
    const pr = pipe.sink.close();
    if (pr) await pr;
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (!/^EPIPE\b/.test(msg)) {
      try {
        const code = e instanceof JsEngineErrorImpl ? e.code : "E20001";
        const pr2 = pipe.sink.close(JSON.stringify({ "code": code, "message": msg }));
        if (pr2) await pr2;
      } catch (_e) {}
    }
  } finally {
    pipe.source.close();
  }
});
