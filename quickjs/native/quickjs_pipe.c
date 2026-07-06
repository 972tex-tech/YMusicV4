#include "quickjs_pipe.h"
#include <stdlib.h>
#include <string.h>

/* Keep at most this many idle segments (32 * 8 KB = 256 KB); beyond that, free(). */
#define POOL_MAX_FREE 32

struct QjsPool {
    void *free_list; /* singly linked through each idle segment's first word */
    int free_count;
};

static uint8_t *pool_take(QjsPool *p) {
    if (p->free_list) {
        uint8_t *seg = (uint8_t *)p->free_list;
        p->free_list = *(void **)seg;
        p->free_count--;
        return seg;
    }
    return (uint8_t *)malloc(QJS_SEG_SIZE);
}

static void pool_put(QjsPool *p, uint8_t *seg) {
    if (p->free_count >= POOL_MAX_FREE) {
        free(seg);
        return;
    }
    *(void **)seg = p->free_list;
    p->free_list = seg;
    p->free_count++;
}

/* free_func of pooled segments — runs on GC finalize and on JS_DetachArrayBuffer (__segRelease).
 * After a detach the finalizer fires again with ptr == NULL; ignore that. */
static void seg_free(JSRuntime *rt, void *opaque, void *ptr) {
    if (ptr) pool_put((QjsPool *)opaque, (uint8_t *)ptr);
}

/* free_func of one-shot host views — the memory is the host's (pinned only for the call). */
static void noop_free(JSRuntime *rt, void *opaque, void *ptr) {}

/* new Uint8Array(ab), consuming ab. The constructor reads argv[1]/argv[2] unconditionally
 * (interpreter undefined-padding), so pad them explicitly. View, no copy. */
static JSValue new_u8_view(JSContext *ctx, JSValue ab) {
    JSValueConst args[3] = { ab, JS_UNDEFINED, JS_UNDEFINED };
    JSValue u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

/* Resolve a Uint8Array argument to its byte pointer + byte length. Returns NULL and throws on a
 * non-view or detached argument. */
static uint8_t *view_bytes(JSContext *ctx, JSValueConst v, size_t *plen) {
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, v, &ta_off, &ta_len, &bpe);
    if (JS_IsException(ab)) return NULL;
    size_t sz = 0;
    uint8_t *ptr = JS_GetArrayBuffer(ctx, &sz, ab);
    JS_FreeValue(ctx, ab); /* the caller's argv keeps the buffer alive */
    if (!ptr) {
        JS_ThrowTypeError(ctx, "detached buffer");
        return NULL;
    }
    *plen = ta_len * (bpe ? bpe : 1);
    return ptr + ta_off;
}

/* __segTake(src, off, len) -> Uint8Array over a pooled segment holding min(len, 8192) bytes copied
 * from src[off..]. JS loops for larger ranges. */
static JSValue js_seg_take(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    QjsPool *pool = (QjsPool *)JS_GetRuntimeOpaque(JS_GetRuntime(ctx));
    int64_t off = 0, len = 0;
    JS_ToInt64(ctx, &off, argv[1]);
    JS_ToInt64(ctx, &len, argv[2]);
    size_t src_len = 0;
    uint8_t *src = view_bytes(ctx, argv[0], &src_len);
    if (!src) return JS_EXCEPTION;
    if (off < 0 || len <= 0 || (uint64_t)off + (uint64_t)len > src_len)
        return JS_ThrowRangeError(ctx, "__segTake range out of bounds");
    size_t n = (size_t)len > QJS_SEG_SIZE ? QJS_SEG_SIZE : (size_t)len;
    uint8_t *seg = pool_take(pool);
    if (!seg) return JS_ThrowOutOfMemory(ctx);
    memcpy(seg, src + off, n);
    JSValue ab = JS_NewArrayBuffer(ctx, seg, n, seg_free, pool, 0);
    if (JS_IsException(ab)) {
        pool_put(pool, seg);
        return ab;
    }
    return new_u8_view(ctx, ab);
}

/* __segRelease(u8) — eagerly return a fully-consumed pool segment. Detach calls seg_free once;
 * the later GC finalizer sees ptr == NULL. Only ever called on __segTake results. */
static JSValue js_seg_release(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    size_t ta_off = 0, ta_len = 0, bpe = 0;
    JSValue ab = JS_GetTypedArrayBuffer(ctx, argv[0], &ta_off, &ta_len, &bpe);
    if (JS_IsException(ab)) return ab;
    JS_DetachArrayBuffer(ctx, ab);
    JS_FreeValue(ctx, ab);
    return JS_UNDEFINED;
}

/* __utf8Decode(u8, off, len) -> string. Native UTF-8 -> JS string (astral-correct); replaces the
 * interpreted per-codepoint decode loop. */
static JSValue js_utf8_decode(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t off = 0, len = 0;
    JS_ToInt64(ctx, &off, argv[1]);
    JS_ToInt64(ctx, &len, argv[2]);
    size_t src_len = 0;
    uint8_t *src = view_bytes(ctx, argv[0], &src_len);
    if (!src) return JS_EXCEPTION;
    if (off < 0 || len < 0 || (uint64_t)off + (uint64_t)len > src_len)
        return JS_ThrowRangeError(ctx, "__utf8Decode range out of bounds");
    return JS_NewStringLen(ctx, (const char *)(src + off), (size_t)len);
}

QjsPool *qjs_register_pipe(JSContext *ctx) {
    QjsPool *pool = (QjsPool *)calloc(1, sizeof(QjsPool));
    JS_SetRuntimeOpaque(JS_GetRuntime(ctx), pool);
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__segTake", JS_NewCFunction(ctx, js_seg_take, "__segTake", 3));
    JS_SetPropertyStr(ctx, g, "__segRelease", JS_NewCFunction(ctx, js_seg_release, "__segRelease", 1));
    JS_SetPropertyStr(ctx, g, "__utf8Decode", JS_NewCFunction(ctx, js_utf8_decode, "__utf8Decode", 3));
    JS_FreeValue(ctx, g);
    return pool;
}

void qjs_pool_free(QjsPool *pool) {
    if (!pool) return;
    void *seg = pool->free_list;
    while (seg) {
        void *next = *(void **)seg;
        free(seg);
        seg = next;
    }
    free(pool);
}

JSValue qjs_view_wrap(JSContext *ctx, const uint8_t *ptr, size_t len, JSValue *out_ab) {
    *out_ab = JS_UNDEFINED;
    JSValue ab = JS_NewArrayBuffer(ctx, (uint8_t *)ptr, len, noop_free, NULL, 0);
    if (JS_IsException(ab)) return ab;
    *out_ab = JS_DupValue(ctx, ab);
    return new_u8_view(ctx, ab);
}

void qjs_view_detach(JSContext *ctx, JSValue view, JSValue ab) {
    if (!JS_IsUndefined(ab)) JS_DetachArrayBuffer(ctx, ab);
    JS_FreeValue(ctx, ab);
    JS_FreeValue(ctx, view);
}
