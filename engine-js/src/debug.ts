// DEBUG-level log. Matches Kotlin `Logger.debug { }`: the thunk is not called when
// PRODUCTION is true (esbuild --define from -Pproduction). Put log-only work inside
// the thunk — production must not build those strings.
// Use debugRun for a void block (multi-line dumps). Do not use `if (!PRODUCTION) { helper() }`:
// Closure ADVANCED drops the helper then still type-checks the dead branch.

export function debug(msg: () => string): void {
  if (!PRODUCTION) console.log(msg());
}

export function debugRun(run: () => void): void {
  if (!PRODUCTION) run();
}
