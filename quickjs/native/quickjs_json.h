/*
 * Native JSON <-> bytes, shared by the JNI and cinterop shims. Registers:
 *   - globalThis.__parseJson(u8) — QuickJS's own JS_ParseJSON directly over the Uint8Array's
 *     UTF-8 bytes: no intermediate JS string, no UTF-8<->UTF-16 marshal.
 *   - globalThis.__stringifyJson(value) -> Uint8Array — native JSON.stringify + native
 *     UTF-16->UTF-8; the encode mirror (never runs through the interpreted utf8Encode).
 * Stateless (uses only the context + args), so both shims can register them without knowing
 * each other's engine struct.
 */
#ifndef QUICKJS_JSON_H
#define QUICKJS_JSON_H

#include <stddef.h>
#include <stdint.h>
#include "quickjs.h"

void qjs_register_json(JSContext *ctx);

/* C-level streaming-parser API (the same push parser behind __jsonPush/Done/End) for callers that
 * live in C and don't need the JSValue wrapper. */
#define QJS_JSON_EXPECT_ANY    0
#define QJS_JSON_EXPECT_OBJECT 1
#define QJS_JSON_EXPECT_ARRAY  2

typedef struct QjsJStream QjsJStream;

QjsJStream *qjs_jstream_new(JSContext *ctx, int expect); /* NULL: JS exception pending */
/* -> bytes consumed (stops at the end of the top-level value), or -1: JS exception pending. */
int qjs_jstream_push(QjsJStream *s, const uint8_t *data, size_t len);
int qjs_jstream_done(QjsJStream *s);
JSValue qjs_jstream_end(QjsJStream *s); /* the value, or JS_EXCEPTION on incomplete/malformed */
void qjs_jstream_free(QjsJStream *s);   /* always required (end() does not free); NULL-safe */
/* The parser holds half-built containers + the result — a holder's gc_mark must forward here. */
void qjs_jstream_mark(JSRuntime *rt, QjsJStream *s, JS_MarkFunc *mark_func);

#endif
