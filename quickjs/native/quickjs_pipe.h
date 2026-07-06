/*
 * Segment-pool memory + zero-copy view helpers for the pipe transport (shared by the JNI and
 * cinterop shims, like quickjs_json).
 *
 * The host->JS direction's internal buffer is a queue of Uint8Array views managed by the JS mux
 * (bootstrap.js), but the BYTES live in C memory: 8 KB segments from a per-engine pool. JS calls
 *   __segTake(src, off, len) -> Uint8Array   copy <= 8 KB out of a (still-attached) source view
 *                                            into a pooled segment, wrapped zero-copy;
 *   __segRelease(u8)                          eager return of a fully-consumed segment (detach);
 *   __utf8Decode(u8, off, len) -> string      native UTF-8 decode (JS_NewStringLen).
 * A segment's ArrayBuffer free_func returns it to the pool, so GC is the release backstop.
 *
 * qjs_view_wrap/qjs_view_detach carry a host write across the boundary with no copy: the shim pins
 * the host bytes, wraps them as a one-shot non-owning ArrayBuffer view, calls __jsData, then
 * detaches the view (an accidentally retained reference throws on access instead of dangling).
 */
#ifndef QUICKJS_PIPE_H
#define QUICKJS_PIPE_H

#include <stddef.h>
#include <stdint.h>
#include "quickjs.h"

/* Segment size — must match the JS mux's accounting (bootstrap.js). */
#define QJS_SEG_SIZE 8192

typedef struct QjsPool QjsPool;

/* Create the engine's segment pool (stored via JS_SetRuntimeOpaque) and register the JS natives.
 * Free the returned pool with qjs_pool_free AFTER JS_FreeRuntime: live segments are returned to
 * the pool by their free_func during runtime teardown. */
QjsPool *qjs_register_pipe(JSContext *ctx);
void qjs_pool_free(QjsPool *pool);

/* Wrap [ptr, ptr+len) as a one-shot zero-copy Uint8Array over a NON-owning ArrayBuffer. *out_ab
 * receives an owned ref to the ArrayBuffer; after the JS call that consumes the view returns, pass
 * both to qjs_view_detach (which detaches and frees them). len must be > 0. */
JSValue qjs_view_wrap(JSContext *ctx, const uint8_t *ptr, size_t len, JSValue *out_ab);
void qjs_view_detach(JSContext *ctx, JSValue view, JSValue ab);

#endif
