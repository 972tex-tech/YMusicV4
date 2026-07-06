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

/* Iterate the unread byte spans of the JS-side inbound queue — head[headPos..] then
 * queue[qpos..qlen) — resolving each segment's pointer on demand. Pointers are only used within
 * the current native call (nothing here runs user JS between resolution and use). */
typedef struct {
    JSContext *ctx;
    JSValueConst head; /* Uint8Array or null */
    uint32_t head_pos;
    JSValueConst queue; /* Array of Uint8Array segment views */
    uint32_t qpos, qlen;
    uint32_t next; /* 0 = head, else queue[qpos + next - 1] */
} SpanIter;

static int span_next(SpanIter *it, const uint8_t **pp, uint32_t *plen) {
    for (;;) {
        if (it->next == 0) {
            it->next = 1;
            if (!JS_IsNull(it->head) && !JS_IsUndefined(it->head)) {
                size_t len = 0;
                uint8_t *p = view_bytes(it->ctx, it->head, &len);
                if (!p) return -1;
                if (it->head_pos < len) {
                    *pp = p + it->head_pos;
                    *plen = (uint32_t)(len - it->head_pos);
                    return 1;
                }
            }
            continue;
        }
        uint32_t qi = it->qpos + it->next - 1;
        if (qi >= it->qlen) return 0;
        it->next++;
        JSValue seg = JS_GetPropertyUint32(it->ctx, it->queue, qi);
        if (JS_IsException(seg)) return -1;
        size_t len = 0;
        uint8_t *p = view_bytes(it->ctx, seg, &len);
        JS_FreeValue(it->ctx, seg); /* the queue keeps it alive */
        if (!p) return -1;
        if (len == 0) continue;
        *pp = p;
        *plen = (uint32_t)len;
        return 1;
    }
}

/* __lineScan(head, headPos, queue, qpos, qlen, scanFrom, takeRestAtEof, outI32) ->
 *   string : a complete line — trailing '\r' stripped, outI32[0] = bytes to consume (through the
 *            '\n'); or, when takeRestAtEof, the final unterminated remainder (outI32[0] = all of it).
 *   null   : no '\n' in the buffered bytes — outI32[0] = bytes scanned (the caller's resume point).
 * The scan is memchr per segment (resumable via scanFrom, so bytes are scanned once); the decode is
 * ONE JS_NewStringLen, with a segment-spanning line assembled once. Consumption (segment release,
 * `buffered` accounting, the DRAIN notify) stays in JS — this only reads. */
static JSValue js_line_scan(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    int64_t head_pos = 0, qpos = 0, qlen = 0, scan_from = 0;
    JS_ToInt64(ctx, &head_pos, argv[1]);
    JS_ToInt64(ctx, &qpos, argv[3]);
    JS_ToInt64(ctx, &qlen, argv[4]);
    JS_ToInt64(ctx, &scan_from, argv[5]);
    int take_rest = JS_ToBool(ctx, argv[6]);
    size_t out_len = 0;
    uint8_t *out_raw = view_bytes(ctx, argv[7], &out_len);
    if (!out_raw) return JS_EXCEPTION;
    if (out_len < 8) return JS_ThrowTypeError(ctx, "__lineScan: out buffer too small");
    int32_t *out = (int32_t *)out_raw; /* [0] = bytes to consume / resume point, [1] = content bytes */

    SpanIter it = { ctx, argv[0], (uint32_t)head_pos, argv[2], (uint32_t)qpos, (uint32_t)qlen, 0 };
    const uint8_t *p;
    uint32_t len;
    uint64_t base = 0, total = 0, skip = (uint64_t)scan_from;
    int prev = -1;  /* the byte just before the scan position (for '\r' stripping across spans) */
    int64_t nl = -1; /* logical index of the '\n' */
    int r;
    while ((r = span_next(&it, &p, &len)) > 0) {
        total += len;
        if (skip >= len) { skip -= len; prev = p[len - 1]; base += len; continue; }
        uint32_t off = (uint32_t)skip;
        if (off > 0) prev = p[off - 1];
        skip = 0;
        const uint8_t *hit = (const uint8_t *)memchr(p + off, '\n', len - off);
        if (hit) {
            nl = (int64_t)(base + (uint64_t)(hit - p));
            if (hit > p) prev = hit[-1];
            break;
        }
        prev = p[len - 1];
        base += len;
    }
    if (r < 0) return JS_EXCEPTION;
    if (nl < 0 && (!take_rest || total == 0)) {
        out[0] = (int32_t)total;
        out[1] = 0;
        return JS_NULL;
    }
    uint64_t e = (nl >= 0) ? ((uint64_t)nl - ((nl > 0 && prev == '\r') ? 1 : 0)) : total;
    uint64_t consume = (nl >= 0) ? (uint64_t)nl + 1 : total;
    JSValue str;
    if (e == 0) {
        str = JS_NewStringLen(ctx, "", 0);
    } else {
        SpanIter it2 = { ctx, argv[0], (uint32_t)head_pos, argv[2], (uint32_t)qpos, (uint32_t)qlen, 0 };
        r = span_next(&it2, &p, &len);
        if (r <= 0) return r < 0 ? JS_EXCEPTION : JS_ThrowInternalError(ctx, "__lineScan: window changed");
        if ((uint64_t)len >= e) {
            str = JS_NewStringLen(ctx, (const char *)p, (size_t)e); /* single-segment fast path */
        } else {
            uint8_t *tmp = (uint8_t *)malloc((size_t)e);
            if (!tmp) return JS_ThrowOutOfMemory(ctx);
            memcpy(tmp, p, len);
            uint64_t got = len;
            while (got < e && (r = span_next(&it2, &p, &len)) > 0) {
                uint32_t n = (uint32_t)((e - got < len) ? (e - got) : len);
                memcpy(tmp + got, p, n);
                got += n;
            }
            if (r < 0 || got < e) {
                free(tmp);
                return r < 0 ? JS_EXCEPTION : JS_ThrowInternalError(ctx, "__lineScan: window changed");
            }
            str = JS_NewStringLen(ctx, (const char *)tmp, (size_t)e);
            free(tmp);
        }
    }
    if (JS_IsException(str)) return str;
    out[0] = (int32_t)consume;
    out[1] = (int32_t)e;
    return str;
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
    JS_SetPropertyStr(ctx, g, "__lineScan", JS_NewCFunction(ctx, js_line_scan, "__lineScan", 8));
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
