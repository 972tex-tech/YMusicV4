// App-facing player commands. init warms the singleton WEB player; load stamps the video id and
// calls Application.loadVideoByPlayerVars. play/pause/seek/stop are playVideo/pauseVideo/seekTo.

import { onAccept } from "../transport";
import { commandHandler, JsEngineErrorImpl } from "../protocol/command";
import { createHtmlMedia } from "../protocol/mse";
import {
  initPlayer,
  loadPlayer,
  mintPlayerPo,
  pausePlayer,
  playPlayer,
  seekPlayer,
  setPlayerQuality,
  statusPlayer,
  stopPlayer,
} from "./player/boot";

createHtmlMedia();

onAccept(
  "player/init",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
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
    const quality = a["quality"] != null ? String(a["quality"]) : undefined;
    const result = await loadPlayer(id, quality);
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
    setPlayerQuality(quality);
    const p = out.writeObjectAsJson({ "ok": true, "quality": quality });
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
