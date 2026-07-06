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

/* Advance over UTF-8 (WTF-8) by `units` UTF-16 code units (astral = 2 units), clamped to end. */
static const uint8_t *utf8_advance(const uint8_t *p, const uint8_t *end, int64_t units) {
    while (units > 0 && p < end) {
        uint8_t c = *p;
        int adv = (c < 0x80) ? 1 : ((c & 0xE0) == 0xC0) ? 2 : ((c & 0xF0) == 0xE0) ? 3
                 : ((c & 0xF8) == 0xF0) ? 4 : 1;
        if (p + adv > end) break;
        units -= (adv == 4) ? 2 : 1;
        p += adv;
    }
    return p;
}

/* __utf8Into(str, from, to, dst, dstOff) -> bytes written, or -(bytes needed) when the encoding of
 * str[from, to) (UTF-16 unit indices) does not fit in dst[dstOff..] — nothing written then; the
 * caller flushes and retries (or falls back to the JS stream encoder for a segment-sized range).
 * One native call replaces the per-codepoint interpreted encode loop. */
static JSValue js_utf8_into(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t from = 0, to = 0, dst_off = 0;
    JS_ToInt64(ctx, &from, argv[1]);
    JS_ToInt64(ctx, &to, argv[2]);
    JS_ToInt64(ctx, &dst_off, argv[4]);
    size_t dlen = 0;
    uint8_t *dst = view_bytes(ctx, argv[3], &dlen);
    if (!dst) return JS_EXCEPTION;
    if (from < 0 || to < from || dst_off < 0 || (uint64_t)dst_off > dlen)
        return JS_ThrowRangeError(ctx, "__utf8Into range out of bounds");
    size_t slen = 0;
    const char *cs = JS_ToCStringLen(ctx, &slen, argv[0]);
    if (!cs) return JS_EXCEPTION;
    const uint8_t *s = (const uint8_t *)cs, *end = s + slen;
    const uint8_t *p0 = utf8_advance(s, end, from);
    const uint8_t *p1 = utf8_advance(p0, end, to - from);
    int64_t need = (int64_t)(p1 - p0);
    JSValue ret;
    if (need > (int64_t)(dlen - (size_t)dst_off)) {
        ret = JS_NewInt64(ctx, -need);
    } else {
        memcpy(dst + dst_off, p0, (size_t)need);
        ret = JS_NewInt64(ctx, need);
    }
    JS_FreeCString(ctx, cs);
    return ret;
}

/* __utf8Bytes(str, from, to) -> Uint8Array: the UTF-8 encoding of str[from, to) (UTF-16 unit
 * indices) as one native encode + copy. Used for a string bigger than the sink segment, where the
 * fits-into-segment path (__utf8Into) can't apply: one materialization of that single token beats
 * the per-codepoint interpreted loop by ~30x, and the result streams through the writeBytes
 * bypass. */
static JSValue js_utf8_bytes(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t from = 0, to = 0;
    JS_ToInt64(ctx, &from, argv[1]);
    JS_ToInt64(ctx, &to, argv[2]);
    if (from < 0 || to < from) return JS_ThrowRangeError(ctx, "__utf8Bytes range out of bounds");
    size_t slen = 0;
    const char *cs = JS_ToCStringLen(ctx, &slen, argv[0]);
    if (!cs) return JS_EXCEPTION;
    const uint8_t *s = (const uint8_t *)cs, *end = s + slen;
    const uint8_t *p0 = utf8_advance(s, end, from);
    const uint8_t *p1 = utf8_advance(p0, end, to - from);
    JSValue ab = JS_NewArrayBufferCopy(ctx, p0, (size_t)(p1 - p0));
    JS_FreeCString(ctx, cs);
    if (JS_IsException(ab)) return ab;
    return new_u8_view(ctx, ab);
}

QjsPool *qjs_register_pipe(JSContext *ctx) {
    QjsPool *pool = (QjsPool *)calloc(1, sizeof(QjsPool));
    JS_SetRuntimeOpaque(JS_GetRuntime(ctx), pool);
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__segTake", JS_NewCFunction(ctx, js_seg_take, "__segTake", 3));
    JS_SetPropertyStr(ctx, g, "__segRelease", JS_NewCFunction(ctx, js_seg_release, "__segRelease", 1));
    JS_SetPropertyStr(ctx, g, "__utf8Decode", JS_NewCFunction(ctx, js_utf8_decode, "__utf8Decode", 3));
    JS_SetPropertyStr(ctx, g, "__utf8Into", JS_NewCFunction(ctx, js_utf8_into, "__utf8Into", 5));
    JS_SetPropertyStr(ctx, g, "__utf8Bytes", JS_NewCFunction(ctx, js_utf8_bytes, "__utf8Bytes", 3));
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
