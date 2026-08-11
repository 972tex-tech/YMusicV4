import { commandHandler } from "../../protocol/command";
import { Settings } from "../../protocol/settings";
import { onAccept } from "../../transport";

onAccept(
  "test/settings",
  commandHandler(async (src, out) => {
    const args = await src.readJsonObject();
    const key = String(args["key"]);
    const type = String(args["type"]);
    const value = args["value"];
    const fallback = args["default"];
    if (value !== undefined) await Settings.set(key, value as string | boolean | number);
    const result = await Settings.get(key, fallback as string | boolean | number);
    const pending = out.writeUtf8(type + ":" + String(result));
    if (pending) await pending;
  }),
);
