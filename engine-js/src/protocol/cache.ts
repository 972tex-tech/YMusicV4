// Cache — the JS client for the host-backed **scoped** keyed blob cache, over the :quickjs pipe
// protocol (host side: CacheProtocol.kt / FileSystemCacheStore). Every op names a scope (an
// independent partition — its own keys, listing, and eviction), so e.g. a home-feed scope and a
// downloads scope coexist. Values STREAM both ways; each entry carries host-stamped metadata
// (size/updatedAt/lastUsed) plus a caller `meta` map. Eviction is OURS (see scopedCache + evictors) —
// the host store is unbounded. One one-shot pipe per call, like http/cookie. Import-only: no
// globalThis, no ambient declaration. Bundled into :engine-js (never shipped as source).

import { callHost } from "./command";

export interface CacheMeta {
  key: string;
  size: number;
  /** Last write (epoch ms). */
  updatedAt: number;
  /** Last access — read or write (epoch ms). Bumped on get; drives LRU. */
  lastUsed: number;
  /** The caller's opaque key-value metadata, echoed back. */
  meta: Record<string, string>;
}

function toMeta(o: Record<string, unknown>): CacheMeta {
  return {
    key: String(o["key"]),
    size: Number(o["size"]),
    updatedAt: Number(o["updatedAt"]),
    lastUsed: Number(o["lastUsed"]),
    meta: (o["meta"] as Record<string, string>) || {},
  };
}

// PUT: `write` streams the value into the sink (e.g. sink.writeObjectAsJson(obj)). Wire keys QUOTED.
function put(
  scope: string,
  key: string,
  write: (sink: PipeSink) => unknown,
  meta?: Record<string, string>,
): Promise<CacheMeta> {
  return callHost("cache/put", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({ "scope": scope, "key": key, "meta": meta || {} });
    if (p) await p;
    await write(pipe.sink); // the value body, right after the self-framing head
    p = pipe.sink.close(); // half-close = body complete
    if (p) await p;
    return toMeta(await pipe.source.readJsonObject());
  });
}

function setJson(
  scope: string,
  key: string,
  value: unknown,
  meta?: Record<string, string>,
): Promise<CacheMeta> {
  return put(scope, key, (sink) => sink.writeObjectAsJson(value), meta);
}

function setText(
  scope: string,
  key: string,
  value: string,
  meta?: Record<string, string>,
): Promise<CacheMeta> {
  return put(scope, key, (sink) => sink.writeUtf8(value), meta);
}

// GET: `read` consumes the value stream (+ its metadata) inside the call; resolves null on a miss.
function get<T>(
  scope: string,
  key: string,
  read: (source: PipeSource, meta: CacheMeta) => T | Promise<T>,
): Promise<T | null> {
  return callHost("cache/get", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({ "scope": scope, "key": key });
    if (p) await p;
    p = pipe.sink.close(); // half-close, no body
    if (p) await p;
    const head = await pipe.source.readJsonObject();
    if (!head["hit"]) return null;
    return await read(pipe.source, toMeta(head));
  });
}

function getJson(scope: string, key: string): Promise<unknown | null> {
  return get(scope, key, (source) => source.readJsonObject());
}

function getText(scope: string, key: string): Promise<string | null> {
  return get(scope, key, (source) => source.readUtf8());
}

function del(scope: string, key: string): Promise<void> {
  return callHost("cache/delete", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({ "scope": scope, "key": key });
    if (p) await p;
    p = pipe.sink.close();
    if (p) await p;
    await pipe.source.readUtf8(); // to EOF: empty on success, throws the decoded RESET on failure
  });
}

function list(scope: string): Promise<CacheMeta[]> {
  return callHost("cache/list", async function (pipe) {
    let p = pipe.sink.writeObjectAsJson({ "scope": scope });
    if (p) await p;
    p = pipe.sink.close();
    if (p) await p;
    return (await pipe.source.readJsonArray()).map((o) => toMeta(o as Record<string, unknown>));
  });
}

export const Cache = {
  put: put,
  setJson: setJson,
  setText: setText,
  get: get,
  getJson: getJson,
  getText: getText,
  delete: del,
  list: list,
};

// ---- eviction policy: a stateful evictor the JS owns (the host store is unbounded) ----

/**
 * Tracks a scope's entries in memory and decides what to evict — stateful so it never re-lists the
 * store. `scopedCache` seeds it once ([init]), fires [onAdded] after every write and [onRead] after
 * every get (a get bumped `lastUsed`), and [onDeleted] on an external delete. [init]/[onAdded] return
 * the entries to evict and drop them from the evictor's own state; [onRead]/[onDeleted] just update it.
 */
export interface CacheEvictor {
  /** Seed from the scope's current entries (persisted across sessions); return any to evict now. */
  init(entries: CacheMeta[]): CacheMeta[];
  /** An entry was written — add OR update (replace by `meta.key`); return any to evict. */
  onAdded(meta: CacheMeta): CacheMeta[];
  /** A `get` bumped an entry's `lastUsed` — update recency for LRU (no eviction; a read adds nothing). */
  onRead(meta: CacheMeta): void;
  /** An entry was deleted externally — drop it from state. */
  onDeleted(key: string): void;
}

// Build a CacheEvictor from a pure "pick the victims out of the current entries" policy; the helper
// keeps the entry map and removes the chosen victims from it (recomputing from the full set, so an
// update replaces cleanly). Implement CacheEvictor by hand for a smarter incremental policy.
export function evictorOf(pickVictims: (entries: CacheMeta[]) => CacheMeta[]): CacheEvictor {
  const state = new Map<string, CacheMeta>();
  function apply(): CacheMeta[] {
    const victims = pickVictims([...state.values()]);
    for (const v of victims) state.delete(v.key);
    return victims;
  }
  return {
    init(entries) {
      state.clear();
      for (const e of entries) state.set(e.key, e);
      return apply();
    },
    onAdded(meta) {
      state.set(meta.key, meta); // add or replace (update)
      return apply();
    },
    onRead(meta) {
      state.set(meta.key, meta); // refresh recency; no trim — a read adds nothing
    },
    onDeleted(key) {
      state.delete(key);
    },
  };
}

/** Keep the [n] most-recently-**written** entries (by updatedAt). */
export function keepCount(n: number): CacheEvictor {
  return evictorOf((e) =>
    e
      .slice()
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(n),
  );
}

/** LRU by count: keep the [n] most-recently-**used** entries (by lastUsed). */
export function lruCount(n: number): CacheEvictor {
  return evictorOf((e) =>
    e
      .slice()
      .sort((a, b) => b.lastUsed - a.lastUsed)
      .slice(n),
  );
}

/** LRU by size: keep the most-recently-used entries whose sizes fit [maxBytes]. */
export function lruSize(maxBytes: number): CacheEvictor {
  return evictorOf((e) => {
    const byRecency = e.slice().sort((a, b) => b.lastUsed - a.lastUsed);
    let total = 0;
    const victims: CacheMeta[] = [];
    for (const x of byRecency) {
      total += x.size;
      if (total > maxBytes) victims.push(x);
    }
    return victims;
  });
}

/**
 * A cache bound to one `scope`, driving [evictor] over its lifecycle: seeded from the scope on
 * creation (hence **async**), fed `onAdded` after every write, `onRead` after every hit (a get bumps
 * `lastUsed`, which the evictor needs for LRU), and `onDeleted` after an explicit delete. Swap the
 * evictor for any policy without an app release.
 */
export async function scopedCache(scope: string, evictor: CacheEvictor) {
  async function evict(victims: CacheMeta[]): Promise<void> {
    for (const v of victims) await Cache.delete(scope, v.key);
  }
  await evict(evictor.init(await Cache.list(scope)));

  async function tracked<T>(
    key: string,
    read: (source: PipeSource, meta: CacheMeta) => T | Promise<T>,
  ): Promise<T | null> {
    const box: { meta?: CacheMeta } = {};
    const out = await Cache.get(scope, key, (source, meta) => {
      box.meta = meta; // a hit bumped lastUsed
      return read(source, meta);
    });
    if (box.meta) evictor.onRead(box.meta); // recency only — a read never evicts
    return out;
  }

  return {
    get: <T>(key: string, read: (source: PipeSource, meta: CacheMeta) => T | Promise<T>) =>
      tracked(key, read),
    getJson: async (key: string) => {
      try {
        return await tracked(key, (source) => source.readJsonObject());
      } catch (e) {
        // A blob torn by a crash before it reached the disk never parses again: drop it, a miss heals.
        if (!(e instanceof SyntaxError)) throw e;
        console.warn("cache " + scope + "/" + key + " unreadable, dropped: " + e.message);
        await Cache.delete(scope, key);
        evictor.onDeleted(key);
        return null;
      }
    },
    getText: (key: string) => tracked(key, (source) => source.readUtf8()),
    list: () => Cache.list(scope),
    delete: async (key: string) => {
      await Cache.delete(scope, key);
      evictor.onDeleted(key);
    },
    put: async (key: string, write: (sink: PipeSink) => unknown, meta?: Record<string, string>) => {
      const m = await Cache.put(scope, key, write, meta);
      await evict(evictor.onAdded(m));
      return m;
    },
    setJson: async (key: string, value: unknown, meta?: Record<string, string>) => {
      const m = await Cache.setJson(scope, key, value, meta);
      await evict(evictor.onAdded(m));
      return m;
    },
    setText: async (key: string, value: string, meta?: Record<string, string>) => {
      const m = await Cache.setText(scope, key, value, meta);
      await evict(evictor.onAdded(m));
      return m;
    },
  };
}
