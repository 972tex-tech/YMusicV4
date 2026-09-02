// Header/method/URL validation and the ordered-multimap header view. The JSON+length framing
// cannot be desynced by header content — these checks protect the REAL HTTP client behind the
// host: reject early with a clear error instead of deep in the transport.

export function checkHeaderName(name: string): string {
  name = String(name);
  if (!/^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/.test(name))
    throw new TypeError('invalid header name: "' + name + '"');
  return name;
}

export function checkHeaderValue(value: string): string {
  value = String(value);
  if (/[\x00\r\n]/.test(value)) throw new TypeError("header value must not contain CR/LF/NUL");
  return value;
}

export function checkMethod(m: string): string {
  m = String(m);
  if (!/^[!#$%&'*+\-.^_`|~0-9A-Za-z]+$/.test(m)) throw new TypeError('invalid method: "' + m + '"');
  return m;
}

export function checkUrl(url: string): string {
  url = String(url);
  if (/[\r\n]/.test(url)) throw new TypeError("URL must not contain CR/LF");
  return url;
}

/** Resolve a fetch/XHR URL against the page location (relative and protocol-relative). */
export function resolveUrl(url: string): string {
  url = String(url || "");
  if (url.indexOf("://") >= 0) return url;
  if (url.indexOf("//") === 0) return "https:" + url;
  const loc = (globalThis as unknown as Record<string, unknown>)["location"] as
    | Record<string, unknown>
    | undefined;
  const origin =
    loc && loc["origin"] != null ? String(loc["origin"]) : "https://www.youtube.com";
  if (url.charAt(0) === "/") return origin + url;
  const href = loc && loc["href"] != null ? String(loc["href"]) : origin + "/";
  const slash = href.lastIndexOf("/");
  return (slash >= 0 ? href.slice(0, slash + 1) : origin + "/") + url;
}

// Coerce untrusted (e.g. scraped) data into a legal header instead of throwing: the name keeps
// only token characters, the value folds CR/LF runs (with surrounding blanks) into one space,
// drops NUL and trims edge whitespace.
export function sanitizeHeaderName(name: string): string {
  name = String(name).replace(/[^!#$%&'*+\-.^_`|~0-9A-Za-z]+/g, "");
  if (name.length === 0) throw new TypeError("header name has no valid characters");
  return name;
}

export function sanitizeHeaderValue(value: string): string {
  return String(value)
    .replace(/\x00/g, "")
    .replace(/[ \t]*[\r\n]+[ \t]*/g, " ")
    .trim();
}

export function normalizeHeaders(
  h: HeaderEntry[] | Record<string, string> | null | undefined,
): HeaderEntry[] {
  if (!h) return [];
  if (Array.isArray(h)) return h.slice(); // copy: we don't mutate the caller's
  return Object.keys(h).map(function (k): HeaderEntry {
    return [k, String(h[k])];
  });
}

/** Case-insensitive presence check over an ordered [[name, value], …] list. */
export function hasHeader(entries: HeaderEntry[], lowerName: string): boolean {
  for (const entry of entries) if (String(entry[0]).toLowerCase() === lowerName) return true;
  return false;
}

export function buildHeaders(entries: HeaderEntry[]) {
  const map = Object.create(null) as Record<string, string | undefined>; // lower-cased name -> joined value
  for (const entry of entries) {
    const key = String(entry[0]).toLowerCase(),
      value = entry[1];
    const prev = map[key];
    map[key] = prev === undefined ? value : prev + ", " + value;
  }
  return {
    entries: entries,
    get: function (name: string): string | null {
      const v = map[String(name).toLowerCase()];
      return v === undefined ? null : v;
    },
    has: function (name: string): boolean {
      return map[String(name).toLowerCase()] !== undefined;
    },
  };
}
