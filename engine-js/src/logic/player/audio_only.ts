// Audio-only is a boolean (player/setIsAudioOnly). YouTube quality is Auto/tiny/hd720/…

function g(): Record<string, unknown> {
  return globalThis as unknown as Record<string, unknown>;
}

export function isAudioOnly(): boolean {
  return !!g()["__ymAudioOnly"];
}

export function setAudioOnly(on: boolean): void {
  g()["__ymAudioOnly"] = on;
}

export function ymQuality(): string {
  return String(g()["__ymQuality"] || "auto");
}

export function setYmQuality(quality: string): void {
  if (quality) g()["__ymQuality"] = quality;
}

/** Product "zero": real video still appends; C decoder is rested. */
export function isLiveVideoRest(): boolean {
  return !!g()["__ymRestVideo"];
}

export function setLiveVideoRest(on: boolean): void {
  g()["__ymRestVideo"] = on;
}

export function isProductZero(): boolean {
  return isAudioOnly() || isLiveVideoRest();
}
