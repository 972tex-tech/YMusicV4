// Audio-only is a boolean. YouTube quality is Auto/tiny/hd720/… — both are one setQuality.

const _globalThis = globalThis as unknown as Record<string, unknown>;

export function isAudioOnly(): boolean {
  return !!_globalThis["__ymAudioOnly"];
}

export function setAudioOnly(on: boolean): void {
  _globalThis["__ymAudioOnly"] = on;
}

export function ymQuality(): string {
  return String(_globalThis["__ymQuality"] || "auto");
}

export function setYmQuality(quality: string): void {
  if (quality) _globalThis["__ymQuality"] = quality;
}
