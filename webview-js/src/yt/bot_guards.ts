import { u8ToBase64, utf8ToU8 } from './utils';

declare global {
  interface Window {
    onPoTokenMinterAvailable: (fn: (array: Uint8Array) => Uint8Array) => (array: Uint8Array) => Uint8Array;
    generatePoTokenForVideo: (videoId: string, timeout?: number) => Promise<string>;
    generatePoTokenForVideoSync: (videoId: string) => string | null;
    waitPoMinter: (timeout?: number) => Promise<void>;
  }
}

export default {};

let poTokenMinter: ((array: Uint8Array) => Uint8Array) | undefined = undefined;
const poTokenCache = new Map<string, string>();
const waiters = new Set<() => void>();
window.onPoTokenMinterAvailable = (fn: (array: Uint8Array) => any) => {
  window.handleYtInternalLog?.('info', 'ymix', `po minter available ${fn != null}`);
  if (fn) {
    poTokenMinter = fn;
    waiters.forEach(waiter => waiter());
    waiters.clear();
  }
  return (array: Uint8Array) => {
    const value = fn(array);
    const key = u8ToBase64(array);
    const valueBase64 = u8ToBase64(value);
    poTokenCache.set(key, valueBase64);
    window.handleYtInternalLog?.('info', 'ymix', `yt.po(${key}) = ${valueBase64}`);
    return value;
  };
};

window.waitPoMinter = function (timeout) {
  if (poTokenMinter != null) return Promise.resolve();
  return new Promise((resolve, reject) => {
    waiters.add(() => resolve());
    if (timeout) setTimeout(reject, timeout);
  });
};

window.generatePoTokenForVideoSync = (videoId: string) => {
  const videoIdBytes = utf8ToU8(videoId);
  const videoIdBase64 = u8ToBase64(videoIdBytes);
  const cachedValue = poTokenCache.get(videoIdBase64);
  if (cachedValue) {
    window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = cached(${cachedValue})`);
    return cachedValue;
  }
  if (poTokenMinter != null) {
    try {
      const res = u8ToBase64(poTokenMinter(videoIdBytes));
      poTokenCache.set(videoIdBase64, res);
      window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = ${res}`);
      return res;
    } catch (error: any) {
      window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = ${error?.stack}`);
    }
  }
  return null;
};

window.generatePoTokenForVideo = function (videoId: string, timeout?: number) {
  return new Promise((resolve, reject) => {
    const videoIdBytes = utf8ToU8(videoId);
    const videoIdBase64 = u8ToBase64(videoIdBytes);
    const cachedValue = poTokenCache.get(videoIdBase64);
    if (cachedValue) {
      window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = cached(${cachedValue})`);
      resolve(cachedValue);
      return;
    }
    if (poTokenMinter != null) {
      try {
        const res = u8ToBase64(poTokenMinter(videoIdBytes));
        poTokenCache.set(videoIdBase64, res);
        window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = ${res}`);
        resolve(res);
      } catch (error: any) {
        window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = ${error?.stack}`);
        reject(error);
      }
      return;
    }
    const waiter = () => {
      if (!poTokenMinter) {
        reject(new Error('Missing po token minter'));
        return;
      }
      try {
        const res = u8ToBase64(poTokenMinter(videoIdBytes));
        poTokenCache.set(videoIdBase64, res);
        window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = ${res}`);
        resolve(res);
      } catch (error: any) {
        window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = ${error?.stack}`);
        reject(error);
      }
    };
    waiters.add(waiter);
    setTimeout(() => {
      if (waiters.delete(waiter)) {
        window.handleYtInternalLog?.('info', 'ymix', `app.po(${videoId}) = timeout`);
        reject(new Error('Timeout'));
      }
    }, timeout || 30000);
  });
};
