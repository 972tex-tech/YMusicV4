// Fixtures for CommandHandlerTest: the error contract driven purely via pipes — Kotlin opens the
// command, sends a JSON head, and asserts on the result bytes / the classified RESET.
import { onAccept } from "../../transport";
import { callHost, callMain, commandHandler, JsEngineErrorImpl } from "../../protocol/command";

onAccept(
  "test/echo",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const p = out.writeUtf8("got:" + a["x"]);
    if (p) await p;
  }),
);

onAccept(
  "test/parse-boom",
  commandHandler(async (src) => {
    await src.readJsonObject();
    throw JsEngineErrorImpl.parse("bad shape");
  }),
);

// The HOST direction: Kotlin serves "hostcmd/<name>" via its commandHandler; these open + decode via
// callHost. HostCommandTest registers the host side, drives test/callhost/<name>, and asserts on the
// reported outcome ("OK:"+result, or "ERR:"+code+":"+message from the classified RESET).
onAccept(
  "test/callhost/echo",
  commandHandler(async (src, out) => {
    await src.readJsonObject(); // consume the drive head
    const r = await callHost("hostcmd/echo", async (p) => {
      const w = p.sink.writeUtf8("ping");
      if (w) await w;
      await p.sink.close(); // END our write; the host echoes then ENDs
      return await p.source.readUtf8();
    });
    const o = out.writeUtf8("OK:" + r);
    if (o) await o;
  }),
);

onAccept(
  "test/callhost/boom",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    try {
      await callHost("hostcmd/boom", async (p) => {
        await p.source.readUtf8(); // reads until the host RESETs — that throw is decoded by callHost
      });
      const o = out.writeUtf8("no-throw");
      if (o) await o;
    } catch (e) {
      const err = e as JsEngineError;
      const o = out.writeUtf8("ERR:" + err.code + ":" + err.message);
      if (o) await o;
    }
  }),
);

onAccept(
  "test/main/echo",
  commandHandler(async (src, out) => {
    const a = await src.readJsonObject();
    const r = await callMain("test/echo", async (p) => {
      const w = p.sink.writeObjectAsJson({ "x": a["x"] });
      if (w) await w;
      await p.sink.close();
      return await p.source.readUtf8();
    });
    const o = out.writeUtf8(r);
    if (o) await o;
  }),
);

onAccept(
  "test/main/missing",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    try {
      await callMain("test/does-not-exist", async (p) => await p.source.readUtf8());
      const o = out.writeUtf8("no-throw");
      if (o) await o;
    } catch (e) {
      const err = e as JsEngineError;
      const o = out.writeUtf8("ERR:" + err.code + ":" + err.message);
      if (o) await o;
    }
  }),
);

onAccept(
  "test/main/boom",
  commandHandler(async (src, out) => {
    await src.readJsonObject();
    try {
      await callMain("test/parse-boom", async (p) => {
        const w = p.sink.writeObjectAsJson({});
        if (w) await w;
        await p.sink.close();
        return await p.source.readUtf8();
      });
      const o = out.writeUtf8("no-throw");
      if (o) await o;
    } catch (e) {
      const err = e as JsEngineError;
      const o = out.writeUtf8("ERR:" + err.code + ":" + err.message);
      if (o) await o;
    }
  }),
);
