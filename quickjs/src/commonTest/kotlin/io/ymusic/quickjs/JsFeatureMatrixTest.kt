package io.ymusic.quickjs

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the JS feature support of the vendored QuickJS (native/quickjs, see VERSION.commit) by
 * probing the REAL runtime — the ground truth behind `quickjs/JS_FEATURES.md`. A vendor bump that
 * changes any answer fails here first, so the doc never drifts from the engine.
 */
class JsFeatureMatrixTest {

    // Every name here must probe truthy. Syntax probes go through eval() so an unsupported form
    // reports itself instead of killing the whole script.
    private val supported = listOf(
        "optional-chaining", "nullish-coalescing", "logical-assignment", "numeric-separators",
        "exponent-operator", "spread-destructuring", "generators", "async-generators",
        "for-await", "tagged-templates", "getters-setters", "proxy-reflect", "symbol-async-iterator",
        "class-fields-public", "class-fields-private", "class-private-methods", "class-private-in",
        "class-static-block", "error-cause", "aggregate-error", "globalthis", "bigint",
        "bigint64array", "weakref", "finalization-registry", "atomics", "shared-array-buffer",
        "regexp-named-groups", "regexp-lookbehind", "regexp-dotall", "regexp-sticky",
        "regexp-hasindices", "regexp-unicode-sets",
        "promise-allsettled", "promise-any", "promise-withresolvers",
        "array-at", "array-flat", "array-findlast", "array-tosorted", "array-with",
        "object-hasown", "object-fromentries", "object-groupby", "map-groupby",
        "string-replaceall", "string-matchall", "string-iswellformed",
    )

    // Every name here must probe falsy: missing globals a bundle author might reach for by habit.
    private val unsupported = listOf(
        "intl", "temporal", "array-fromasync", "structured-clone", "queue-microtask",
        "settimeout", "text-encoder", "url", "url-search-params", "fetch", "atob",
        "explicit-resource-management",
    )

    @Test
    fun feature_matrix_matches_documented_support() = runTest {
        val logs = mutableListOf<String>()
        val rt = QuickJsRuntime.create(PROBE_JS, onLog = { _, msg -> logs.add(msg) })
        try {
            val line = logs.firstOrNull { it.startsWith("PROBE ") }
            assertTrue(line != null, "probe script produced no PROBE line; logs=$logs")
            // "name=1" / "name=0" pairs, comma-joined (no JSON parser needed on this side).
            val results = line.removePrefix("PROBE ").split(",").associate {
                val (k, v) = it.split("="); k to (v == "1")
            }
            println("QuickJS feature matrix (${results.size} probes):")
            results.entries.sortedBy { it.key }.forEach { println("  ${if (it.value) "yes" else "NO "} ${it.key}") }
            val wrongMissing = supported.filter { results[it] != true }
            val wrongPresent = unsupported.filter { results[it] != false }
            assertTrue(
                wrongMissing.isEmpty() && wrongPresent.isEmpty(),
                "JS_FEATURES.md is stale — supported-but-probed-missing: $wrongMissing, " +
                    "unsupported-but-probed-present: $wrongPresent. Update quickjs/JS_FEATURES.md with this run's matrix.",
            )
        } finally {
            rt.close()
        }
    }
}

// Probes truthy = supported; a throw (syntax or missing binding) = unsupported.
private val PROBE_JS = """
var r = [];
function p(name, fn) { var ok = 0; try { ok = fn() ? 1 : 0; } catch (e) { ok = 0; } r.push(name + "=" + ok); }
function s(name, src) { p(name, function () { eval(src); return true; }); }

s("optional-chaining", "({a:1})?.a");
s("nullish-coalescing", "null ?? 1");
s("logical-assignment", "var x1 = null; x1 ??= 2;");
s("numeric-separators", "1_000_000");
s("exponent-operator", "2 ** 10");
s("spread-destructuring", "var [a1, ...b1] = [1, 2, 3]; var c1 = {...{d: 1}};");
s("generators", "function* g1() { yield 1; }");
s("async-generators", "async function* g2() { yield 1; }");
s("for-await", "async function f1() { for await (var v of []) {} }");
s("tagged-templates", "String.raw`a`");
s("getters-setters", "({ get a() { return 1; }, set a(v) {} })");
p("proxy-reflect", function () { return typeof Proxy === "function" && typeof Reflect === "object"; });
p("symbol-async-iterator", function () { return typeof Symbol.asyncIterator === "symbol"; });
s("class-fields-public", "class C1 { x = 1; static y = 2; }");
s("class-fields-private", "class C2 { #x = 1; }");
s("class-private-methods", "class C3 { #m() {} static #s() {} }");
s("class-private-in", "class C4 { #x; static has(o) { return #x in o; } }");
s("class-static-block", "class C5 { static { } }");
p("error-cause", function () { return new Error("m", { cause: 42 }).cause === 42; });
p("aggregate-error", function () { return typeof AggregateError === "function"; });
p("globalthis", function () { return typeof globalThis === "object"; });
p("bigint", function () { return typeof BigInt === "function" && (2n ** 64n) > 0n; });
p("bigint64array", function () { return typeof BigInt64Array === "function"; });
p("weakref", function () { return typeof WeakRef === "function"; });
p("finalization-registry", function () { return typeof FinalizationRegistry === "function"; });
p("atomics", function () { return typeof Atomics === "object"; });
p("shared-array-buffer", function () { return typeof SharedArrayBuffer === "function"; });
s("regexp-named-groups", "'a'.match(/(?<g>a)/).groups.g");
s("regexp-lookbehind", "/(?<=a)b/.test('ab')");
p("regexp-dotall", function () { return /a.b/s.test("a\nb"); });
p("regexp-sticky", function () { var re = /a/y; return re.sticky === true; });
s("regexp-hasindices", "if ('a'.match(/a/d).indices[0][0] !== 0) throw 0;");
s("regexp-unicode-sets", "/[\\p{Letter}]/v.test('a')");
p("promise-allsettled", function () { return typeof Promise.allSettled === "function"; });
p("promise-any", function () { return typeof Promise.any === "function"; });
p("promise-withresolvers", function () { return typeof Promise.withResolvers === "function"; });
p("array-at", function () { return [1, 2].at(-1) === 2; });
p("array-flat", function () { return typeof [].flat === "function" && typeof [].flatMap === "function"; });
p("array-findlast", function () { return typeof [].findLast === "function"; });
p("array-tosorted", function () { return typeof [].toSorted === "function" && typeof [].toReversed === "function"; });
p("array-with", function () { return typeof [].with === "function"; });
p("object-hasown", function () { return Object.hasOwn({ a: 1 }, "a"); });
p("object-fromentries", function () { return Object.fromEntries([["a", 1]]).a === 1; });
p("object-groupby", function () { return typeof Object.groupBy === "function"; });
p("map-groupby", function () { return typeof Map.groupBy === "function"; });
p("string-replaceall", function () { return "aa".replaceAll("a", "b") === "bb"; });
p("string-matchall", function () { return typeof "".matchAll === "function"; });
p("string-iswellformed", function () { return typeof "".isWellFormed === "function"; });

p("intl", function () { return typeof Intl !== "undefined"; });
p("temporal", function () { return typeof Temporal !== "undefined"; });
p("array-fromasync", function () { return typeof Array.fromAsync === "function"; });
p("structured-clone", function () { return typeof structuredClone === "function"; });
p("queue-microtask", function () { return typeof queueMicrotask === "function"; });
p("settimeout", function () { return typeof setTimeout === "function"; });
p("text-encoder", function () { return typeof TextEncoder === "function"; });
p("url", function () { return typeof URL === "function"; });
p("url-search-params", function () { return typeof URLSearchParams === "function"; });
p("fetch", function () { return typeof fetch === "function"; });
p("atob", function () { return typeof atob === "function"; });
s("explicit-resource-management", "function f2() { using x2 = { [Symbol.dispose]() {} }; }");

__consoleLog(1, "PROBE " + r.join(","));
"""
