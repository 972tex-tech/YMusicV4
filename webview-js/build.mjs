// Builds the injected WebView script into a single ES5 IIFE the native host evals at document-start,
// before any YouTube page script runs. It is injected into whatever WebView the device happens to
// have, so the goal is maximum backward-compatibility — hence the ES5 downlevel via babel (esbuild
// cannot emit below ES2015). Pipeline, mirroring the source project (ymix-server) toolchain:
//
//   esbuild  — bundle TS + resolve modules + inline defines (NO transpile: target esnext)
//   babel    — @babel/preset-env at { chrome: 37 } → ES5 syntax (block-scoping/classes/async lowered,
//              regenerator inlined; no core-js, so the real RUN floor is ~Chrome 45 where the code's
//              unpolyfilled Map/Set/spread APIs exist — same as ymix-server ships today)
//   terser   — minify at ecma 5 + safari10 (prod only)
//
// Compile-time globals (esbuild --define, = ymix-server's webpack DefinePlugin):
//   DEBUG / FORCE_RE_MISMATCH_CRASH / BUILD_DATE. `ymusicSessionId` is NOT defined here — it is a
//   runtime global the host installs before the script.
import { build as esbuild } from 'esbuild';
import { transformAsync } from '@babel/core';
import presetEnv from '@babel/preset-env';
import { minify } from 'terser';
import { mkdir, writeFile, watch as fsWatch } from 'node:fs/promises';
import { dirname } from 'node:path';

const prod = process.argv.includes('--prod');
const watch = process.argv.includes('--watch');
// --out <path>: write elsewhere (Gradle packaging builds into its own build dir so the dev dist/
// stays whatever the dev loop last produced).
const outIdx = process.argv.indexOf('--out');
const OUT = outIdx !== -1 ? process.argv[outIdx + 1] : 'dist/webview_ymusic.js';

// The oldest browser we intentionally reach. ES5 output parses far below this; the effective floor is
// bounded by the code's unpolyfilled APIs (Map/Set → Chrome 38, iterable spread → Chrome 45).
const BROWSER_TARGET = { chrome: '37' };

async function runOnce() {
  const buildDate = Date.now();

  // 1. esbuild: bundle only, keep modern syntax for babel to lower in one place.
  const bundled = await esbuild({
    entryPoints: ['src/webview_ymusic.ts'],
    bundle: true,
    format: 'iife',
    target: 'esnext',
    platform: 'browser',
    write: false,
    define: {
      DEBUG: String(!prod),
      FORCE_RE_MISMATCH_CRASH: 'false',
      BUILD_DATE: String(buildDate),
    },
    logLevel: 'warning',
  });
  const esm = bundled.outputFiles[0].text;

  // 2. babel: downlevel to ES5 for old WebViews.
  const transpiled = await transformAsync(esm, {
    babelrc: false,
    configFile: false,
    compact: false,
    sourceType: 'script', // esbuild's IIFE is a script, not a module
    presets: [[presetEnv, { targets: BROWSER_TARGET, bugfixes: true }]],
  });
  let code = transpiled.code;

  // 3. terser: minify (prod) at ES5 with the Safari 10 workaround.
  if (prod) {
    const min = await minify(code, {
      ecma: 5,
      safari10: true,
      format: { comments: false },
    });
    code = min.code;
  }

  await mkdir(dirname(OUT), { recursive: true });
  await writeFile(OUT, code);
  console.log(
    `webview-js: built ${OUT} (${prod ? 'prod' : 'dev'}, ES5/chrome${BROWSER_TARGET.chrome}, ` +
      `BUILD_DATE=${buildDate}, ${(code.length / 1024).toFixed(1)}kb)`,
  );
}

await runOnce();

if (watch) {
  console.log('webview-js: watching src/…');
  let pending = null;
  for await (const _ of fsWatch('src', { recursive: true })) {
    clearTimeout(pending);
    pending = setTimeout(() => runOnce().catch((e) => console.error(e)), 100);
  }
}
