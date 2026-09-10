// Two flags: audio-only (boolean) vs YouTube quality (tiny/medium/hd720/auto).
// "zero" is only a player/setQuality wire token; it is not stored as quality.

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
  if (quality && quality !== "zero") g()["__ymQuality"] = quality;
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

/** Experiment: real 144p /player, 2nd SABR vid=278 only. */
export function isVp9SwitchExp(): boolean {
  return !!g()["__ymVp9Switch"];
}

export function setVp9SwitchExp(on: boolean): void {
  g()["__ymVp9Switch"] = on;
}
