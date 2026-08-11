// Fixtures for CacheProtocolTest: drive the scoped Cache client (host side = a FileSystemCacheStore
// over a real temp dir the Kotlin test installs) purely via pipes. Registered as test/cache/<name>.
import { onAccept } from "../../transport";
import { commandHandler } from "../../protocol/command";
import { Cache, scopedCache, keepCount, lruCount, lruSize } from "../../protocol/cache";

type Scenario = (args: Record<string, unknown>) => Promise<string>;

const keysOf = (entries: { key: string }[]) =>
  entries
    .map((m) => m.key)
    .sort()
    .join(",");

const scenarios: Record<string, Scenario> = {
  "set-get-json": async (a) => {
    const meta = await Cache.setJson("s", String(a["key"]), a["value"]);
    const got = await Cache.getJson("s", String(a["key"]));
    return JSON.stringify(got) + "|size=" + meta.size;
  },

  "set-get-text": async (a) => {
    await Cache.setText("s", String(a["key"]), String(a["value"]));
    return String(await Cache.getText("s", String(a["key"])));
  },

  "miss": async (a) => String(await Cache.getJson("s", String(a["key"]))), // "null"

  "custom-meta": async (a) => {
    await Cache.setJson("s", String(a["key"]), { "x": 1 }, { "etag": "abc", "src": "yt" });
    const entry = (await Cache.list("s")).find((m) => m.key === String(a["key"]))!;
    return entry.meta["etag"] + "," + entry.meta["src"];
  },

  // put sets updatedAt==lastUsed; a later put advances updatedAt.
  "put-timestamps": async () => {
    const m1 = await Cache.setText("s", "k", "v1");
    const m2 = await Cache.setText("s", "k", "v2");
    return [m1.updatedAt === m1.lastUsed, m2.updatedAt > m1.updatedAt].join(",");
  },

  // get bumps lastUsed past updatedAt.
  "lastused-bumps": async () => {
    const written = await Cache.setText("s", "k", "v");
    await Cache.getText("s", "k"); // access → bump
    const entry = (await Cache.list("s"))[0]!;
    return String(entry.lastUsed > written.updatedAt);
  },

  // scopes are isolated: a key in scope A is invisible to scope B, and each lists only its own.
  "scope-isolation": async () => {
    await Cache.setText("A", "a", "1");
    await Cache.setText("B", "b", "2");
    const inB = await Cache.getText("A", "b"); // wrong scope → miss
    return keysOf(await Cache.list("A")) + "|" + keysOf(await Cache.list("B")) + "|" + inB;
  },

  "delete": async (a) => {
    const k = String(a["key"]);
    await Cache.setText("s", k, "v");
    await Cache.delete("s", k);
    return String(await Cache.getText("s", k)); // "null"
  },

  // keepCount(2): keep the 2 most-recently-WRITTEN of 3.
  "evict-keep-count": async () => {
    const c = await scopedCache("s", keepCount(2));
    await c.setText("a", "1");
    await c.setText("b", "2");
    await c.setText("c", "3");
    return keysOf(await c.list());
  },

  // lruCount(2): touching 'a' (a get) keeps it alive; the un-used 'b' is evicted when 'c' lands.
  "evict-lru-count": async () => {
    const c = await scopedCache("s", lruCount(2));
    await c.setText("a", "1");
    await c.setText("b", "2");
    await c.getText("a"); // use 'a' → newest lastUsed (the evictor sees it via onAdded)
    await c.setText("c", "3"); // trim: keep a (used) + c (newest), evict b
    return keysOf(await c.list());
  },

  // lruSize(10): a(5)+b(5)+c(1) by recency c,b,a → a spills past the 10-byte budget.
  "evict-lru-size": async () => {
    const c = await scopedCache("s", lruSize(10));
    await c.setText("a", "12345");
    await c.setText("b", "67890");
    await c.setText("c", "X");
    return keysOf(await c.list());
  },
};

for (const name of Object.keys(scenarios)) {
  onAccept(
    "test/cache/" + name,
    commandHandler(async (src, out) => {
      const args = await src.readJsonObject();
      let r: string;
      try {
        r = await scenarios[name]!(args);
      } catch (e) {
        r = "ERR:" + String((e as Error | null)?.message || e);
      }
      const p = out.writeUtf8(r);
      if (p) await p;
    }),
  );
}
