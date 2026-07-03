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

#include "quickjs.h"

void qjs_register_json(JSContext *ctx);

#endif
