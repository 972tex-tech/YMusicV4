// Display viewport YouTube ABR sees (player clientWidth/Height).
// Picture size (adaptiveFormats width/height) is listen/player/status videoWidth/Height.
// Default 360p; host overrides with the real hole size.

const DEFAULT_W = 640;
const DEFAULT_H = 360;

let viewportW = DEFAULT_W;
let viewportH = DEFAULT_H;

export function viewportWidth(): number {
  return viewportW;
}

export function viewportHeight(): number {
  return viewportH;
}

/** Missing/invalid values keep the current size (starts at 640×360). */
export function applyViewport(width: unknown, height: unknown): void {
  const w = Number(width);
  const h = Number(height);
  if (isFinite(w) && w > 0) viewportW = Math.round(w);
  if (isFinite(h) && h > 0) viewportH = Math.round(h);
}
