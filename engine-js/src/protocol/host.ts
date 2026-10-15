// Hosts before versionCode 3 passed a placeholder instead of their version code: 1 (the Android and
// desktop engine) or 5000 (the iOS engine and every player host). Gate on this, not versions.app.
export function hostVersionCode(): number {
  const app = versions.app;
  return app === 1 || app === 5000 ? 0 : app;
}
