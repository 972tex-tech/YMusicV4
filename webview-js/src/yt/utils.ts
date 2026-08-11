export function parseYtText(data: any): string | undefined {
  if (data == null) return;
  if (typeof data === 'string') return data;
  if (typeof data === 'object') {
    if (Array.isArray(data)) {
      return data.map(it => parseYtText(it)).join(' ');
    }
    return parseYtText(data.simpleText)
      ?? parseYtText(data.text)
      ?? parseYtText(data.runs);
  }
}

export interface ChangeStep {
  index: number;
  from: string;
  to: string;
}

export const fixReVarName = (regex: RegExp) => {
  const pattern = regex.toString().replace(/\\w\+/g, '[\\w\\$]+');
  if (DEBUG) {
    // console.log('old', regex.toString());
    // console.log('new', pattern);
  }
  return RegExp(pattern.substring(1, pattern.lastIndexOf('/')), regex.flags);
};

export function regexReplace(source: string, regex: RegExp, replacement: (match: RegExpMatchArray) => string): ChangeStep[] {
  regex = fixReVarName(regex);
  regex.lastIndex = 0;
  const res: ChangeStep[] = [];
  let match = regex.exec(source);
  while (match != null) {
    const replaceStr = replacement(match);
    if (replaceStr == match[0]) {
      if (FORCE_RE_MISMATCH_CRASH) throw new Error(`?? regex replace failure ${regex}`);
      else window.handleYtInternalLog?.('error', 'ymix', `?? regex replace failure ${regex}`);
    }
    res.push({
      index: match.index,
      from: match[0],
      to: replaceStr,
    });

    match = regex.global ? regex.exec(source) : null;
  }
  if (!res.length) {
    if (FORCE_RE_MISMATCH_CRASH) new Error(`regex mismatch ${regex}`);
    else window.handleYtInternalLog?.('error', 'ymix', `regex mismatch ${regex}`);
  }
  return res;
}

export function applyChange(source: string, changes: ChangeStep[]): string {
  if (changes.length == 0) return source;
  const result: string[] = [];
  let cursor = 0;
  for (let i = 0; i < changes.length; i++) {
    const change = changes[i];
    if (change.index < cursor) throw new Error('Unexpected change sequences');
    const head = source.substring(cursor, change.index);
    const body = source.substring(change.index, change.index + change.from.length);
    if (body !== change.from) throw new Error(`Source has changed:\n\tExpect ${change.from}\n\tActual: ${body}`);
    if (head.length) result.push(head);
    result.push(change.to);
    cursor = change.index + change.from.length;
  }
  const tail = source.substring(cursor);
  if (tail.length) result.push(tail);
  return result.join('');
}

const RE_LS_KEY = 'ymusic_re';

export function cacheRegexReplace(key: string, url: string, changes: ChangeStep[]) {
  const lsKey = `${RE_LS_KEY}_${key}`;
  localStorage.setItem(lsKey, JSON.stringify({
    url: url, ver: BUILD_DATE, time: Date.now(),
    changes,
  }));
}

export function applyRegexReplaceCache(key: string, url: string, source: string): string | null {
  const lsKey = `${RE_LS_KEY}_${key}`;
  const cacheData = JSON.parse(localStorage.getItem(lsKey) || '{}');
  if (cacheData.url === url && cacheData.ver === BUILD_DATE) {
    try {
      return applyChange(source, cacheData.changes);
    } catch (e: any) {
      window.handleYtInternalLog?.('error', 'ymix', e?.stack);
      window.ymixWvApi?.reportError(e.message, `${key} ${url}`);
    }
  }
  return null;
}

export function regexReplaceFlow(cacheKey: string, url: string, source: string, replaceImpl: () => (ChangeStep | ChangeStep[])[]): string {
  const cache = applyRegexReplaceCache(cacheKey, url, source);
  if (cache) return cache;
  const changes = replaceImpl().reduce<ChangeStep[]>((finalArray, item) => {
    if (Array.isArray(item)) {
      finalArray.push(...item);
    } else {
      finalArray.push(item);
    }
    return finalArray;
  }, []);

  if (DEBUG && !FORCE_RE_MISMATCH_CRASH) console.log(JSON.stringify(changes, null, 4));
  changes.sort((a, b) => a.index - b.index);
  const result = applyChange(source, changes);
  cacheRegexReplace(cacheKey, url, changes);
  return result;
}

export function parseWatchNextForRecommendedFeed(watchNextJson: any) {
  // Both lists, end screen FIRST: it holds YouTube's own "watch next" picks and the Mix/radio
  // tiles the secondary column never carries. They overlap heavily — the engine dedupes what it
  // stores, so sending both costs nothing but a little JSON.
  const contents = [
    ...(watchNextJson.playerOverlays?.playerOverlayRenderer?.endScreen?.watchNextEndScreenRenderer?.results ?? []),
    ...(watchNextJson.contents?.twoColumnWatchNextResults?.secondaryResults?.secondaryResults?.results ?? []),
  ];
  if (!contents?.length) return;
  const result = [];
  for (let i = 0; i < contents.length; i++) {
    const content = contents[i];
    const itemSectionRenderer = content.itemSectionRenderer?.contents;
    if (itemSectionRenderer?.length) {
      result.push(...itemSectionRenderer);
      continue;
    }
    result.push(content);
  }
  return result;
}

export function proxyMethodPolyfill<T extends object>(method: T, impl: {
  apply: (target: T, thisArg: any, argArray: any[]) => any;
}) {
  if (typeof Proxy != 'undefined') {
    return new Proxy(method, impl);
  }
  return function (this: any, ...args: any[]) {
    return impl.apply(method, this, args);
  };
}

export function proxyConstructor(method: any, impl: {
  construct(target: any, argArray: any[]): object;
}) {
  if (typeof Proxy != 'undefined') {
    return new Proxy(method, impl);
  }

  const fn = function (...args: any[]) {
    return impl.construct(method, args);
  };
  fn.prototype = method.prototype;
  return fn;
}

const base64abc = [
  'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
  'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
  'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
  'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
  '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '+', '/',
];

export function u8ToBase64(bytes: Uint8Array, urlSafe = false) {
  let result = '', i;
  const l = bytes.length;
  for (i = 2; i < l; i += 3) {
    result += base64abc[bytes[i - 2] >> 2];
    result += base64abc[((bytes[i - 2] & 0x03) << 4) | (bytes[i - 1] >> 4)];
    result += base64abc[((bytes[i - 1] & 0x0F) << 2) | (bytes[i] >> 6)];
    result += base64abc[bytes[i] & 0x3F];
  }
  if (i === l + 1) { // 1 octet yet to write
    result += base64abc[bytes[i - 2] >> 2];
    result += base64abc[(bytes[i - 2] & 0x03) << 4];
    result += '==';
  }
  if (i === l) { // 2 octets yet to write
    result += base64abc[bytes[i - 2] >> 2];
    result += base64abc[((bytes[i - 2] & 0x03) << 4) | (bytes[i - 1] >> 4)];
    result += base64abc[(bytes[i - 1] & 0x0F) << 2];
    result += '=';
  }
  if (urlSafe) {
    result = result.replace(/\+/g, '-').replace(/\//g, '_');
  }
  return result;
}

export function base64ToU8(base64: string) {
  const binary = atob(base64); // Decode Base64 to binary string
  const len = binary.length;
  const bytes = new Uint8Array(len);
  for (let i = 0; i < len; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

export function utf8ToU8(str: string): Uint8Array {
  const bytes = [];

  for (let i = 0; i < str.length; i++) {
    let code = str.charCodeAt(i);

    // Handle surrogate pairs (for characters beyond BMP)
    if (code >= 0xD800 && code <= 0xDBFF && i + 1 < str.length) {
      const nextCode = str.charCodeAt(i + 1);
      if (nextCode >= 0xDC00 && nextCode <= 0xDFFF) {
        // Calculate the Unicode code point from surrogate pair
        code = ((code - 0xD800) << 10) + (nextCode - 0xDC00) + 0x10000;
        i++; // Skip the next character as it's part of the pair
      }
    }

    // Convert code point to UTF-8 bytes
    if (code <= 0x7F) {
      // 1 byte
      bytes.push(code);
    } else if (code <= 0x7FF) {
      // 2 bytes
      bytes.push(0xC0 | (code >> 6));
      bytes.push(0x80 | (code & 0x3F));
    } else if (code <= 0xFFFF) {
      // 3 bytes
      bytes.push(0xE0 | (code >> 12));
      bytes.push(0x80 | ((code >> 6) & 0x3F));
      bytes.push(0x80 | (code & 0x3F));
    } else {
      // 4 bytes
      bytes.push(0xF0 | (code >> 18));
      bytes.push(0x80 | ((code >> 12) & 0x3F));
      bytes.push(0x80 | ((code >> 6) & 0x3F));
      bytes.push(0x80 | (code & 0x3F));
    }
  }

  return new Uint8Array(bytes);
}

export function deepClone<T>(value: T): T {
  // Handle primitives and null/undefined
  if (value === null || typeof value !== 'object') {
    return value;
  }

  // Handle Array
  if (Array.isArray(value)) {
    const arrCopy: any[] = [];
    for (let i = 0; i < value.length; i++) {
      arrCopy[i] = deepClone(value[i]);
    }
    return arrCopy as any;
  }

  // Handle Object
  const objCopy: Record<string, any> = {};
  for (const key in value) {
    if (Object.prototype.hasOwnProperty.call(value, key)) {
      objCopy[key] = deepClone((value as any)[key]);
    }
  }

  return objCopy as T;
}

export function generateClientScreenNonce(length = 16) {
  const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
  const result = [];
  for (let i = 0; i < length; i++) {
    result.push(chars.charAt(Math.floor(Math.random() * chars.length)));
  }
  return result.join('');
}

export function makeBase64UrlSafe(base64: string) {
  return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
