import { callHost } from "./command";

type SettingValue = string | boolean | number;

function valueType(value: SettingValue): "string" | "boolean" | "int" {
  if (typeof value === "string") return "string";
  if (typeof value === "boolean") return "boolean";
  if (Number.isInteger(value)) return "int";
  throw new TypeError("settings values must be strings, booleans, or integers");
}

async function get<T extends SettingValue>(key: string, defaultValue: T): Promise<T> {
  return callHost("settings/get", async function (pipe) {
    let pending = pipe.sink.writeObjectAsJson({
      "key": key,
      "type": valueType(defaultValue),
      "default": defaultValue,
    });
    if (pending) await pending;
    pending = pipe.sink.close();
    if (pending) await pending;
    const reply = (await pipe.source.readJsonObject()) as Record<string, unknown>;
    return reply["value"] as T;
  });
}

async function set(key: string, value: SettingValue): Promise<void> {
  return callHost("settings/set", async function (pipe) {
    let pending = pipe.sink.writeObjectAsJson({
      "key": key,
      "type": valueType(value),
      "value": value,
    });
    if (pending) await pending;
    pending = pipe.sink.close();
    if (pending) await pending;
    await pipe.source.readUtf8();
  });
}

export const Settings = { get: get, set: set };
