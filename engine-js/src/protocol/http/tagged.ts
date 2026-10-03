// InnerTube does `text()` then `JSON.parse(e.replace(")]}'",""))`. A primitive cannot hold a
// property, so this is a String object: `replace` keeps the box, `JSON.parse` reads `_jsonParsed`.

export function taggedJsonText(rec: unknown): string {
  const s = new String("{}") as unknown as Record<string, unknown>;
  Object.defineProperty(s, "_jsonParsed", { value: rec, configurable: true });
  Object.defineProperty(s, "replace", {
    value: function () {
      return s;
    },
    configurable: true,
  });
  return s as unknown as string;
}

export function installJsonParseHook(): void {
  const json = JSON as unknown as Record<string, unknown>;
  const orig = json["parse"] as (
    text: string,
    reviver?: (key: string, value: unknown) => unknown,
  ) => unknown;
  const impl = function (text: unknown, reviver?: (key: string, value: unknown) => unknown) {
    if (text != null && typeof text === "object") {
      const rec = (text as Record<string, unknown>)["_jsonParsed"];
      if (rec !== undefined) return rec;
    }
    return orig.call(JSON, text as string, reviver);
  };
  json["parse"] = typeof __nativeFn === "function" ? __nativeFn("JSON.parse", impl) : impl;
}
