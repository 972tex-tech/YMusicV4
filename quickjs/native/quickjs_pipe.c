#include "quickjs_pipe.h"
#include "cutils.h" /* DynBuf (the line splitter's accumulator) */
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

/* From the YMusic patch at the end of the vendored quickjs.c: direct flat-string storage access
 * (narrow = Latin-1, wide = UTF-16; ropes linearize once and cache). */
JSValue JS_ToFlatString(JSContext *ctx, JSValueConst v);
int JS_GetFlatStringStorage(JSValueConst v, const void **pdata, uint32_t *plen);

/* __utf8Into(str, from, to, dst, dstOff) -> (unitsRead << 16) | bytesWritten.
 * Encode str[from, to) (UTF-16 unit indices) straight from flat string storage into dst[dstOff..],
 * stopping on the last whole codepoint that fits (a surrogate pair is never split; a lone
 * surrogate goes out as WTF-8, consistent with the rest of the stack). Chunk-loop friendly: the
 * caller advances from/dstOff and flushes between calls — ONE encode pass total, indexed O(1) per
 * call, no whole-string materialization ever. The per-call encode window is capped at 65535 bytes
 * so both halves fit the packed return (the sink segment is 8 KB anyway). */
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
    JSValue flat = JS_ToFlatString(ctx, argv[0]);
    if (JS_IsException(flat)) return flat;
    const void *data = NULL;
    uint32_t slen = 0;
    int wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "__utf8Into: not a string");
    }
    uint32_t end = (to > (int64_t)slen) ? slen : (uint32_t)to;
    size_t cap = dlen - (size_t)dst_off;
    if (cap > 65535) cap = 65535;
    uint8_t *q = dst + dst_off;
    const uint8_t *qend = q + cap;
    uint32_t i = (uint32_t)from;
    if (i > end) i = end;
    uint32_t start = i;
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        while (i < end) {
            uint8_t c = sp[i];
            if (c < 0x80) {
                if (q + 1 > qend) break;
                *q++ = c;
            } else {
                if (q + 2 > qend) break;
                *q++ = 0xC0 | (c >> 6);
                *q++ = 0x80 | (c & 0x3F);
            }
            i++;
        }
    } else {
        const uint16_t *sp = (const uint16_t *)data;
        while (i < end) {
            uint32_t c = sp[i];
            if (c < 0x80) {
                if (q + 1 > qend) break;
                *q++ = c;
                i++;
            } else if (c < 0x800) {
                if (q + 2 > qend) break;
                *q++ = 0xC0 | (c >> 6);
                *q++ = 0x80 | (c & 0x3F);
                i++;
            } else if (c >= 0xD800 && c < 0xDC00 && i + 1 < end && sp[i + 1] >= 0xDC00 && sp[i + 1] < 0xE000) {
                if (q + 4 > qend) break;
                uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + (sp[i + 1] - 0xDC00);
                *q++ = 0xF0 | (cp >> 18);
                *q++ = 0x80 | ((cp >> 12) & 0x3F);
                *q++ = 0x80 | ((cp >> 6) & 0x3F);
                *q++ = 0x80 | (cp & 0x3F);
                i += 2;
            } else {
                if (q + 3 > qend) break;
                *q++ = 0xE0 | (c >> 12);
                *q++ = 0x80 | ((c >> 6) & 0x3F);
                *q++ = 0x80 | (c & 0x3F);
                i++;
            }
        }
    }
    JS_FreeValue(ctx, flat);
    uint32_t read = i - start;
    uint32_t written = (uint32_t)(q - (dst + dst_off));
    return JS_NewInt32(ctx, (int32_t)((read << 16) | written));
}

/* ---- Native line splitter — the '\n' mirror of the streaming JSON parser (the __jsonNew/Push/
 * Done/End pattern). Bytes are CONSUMED as they are fed, so the reader always drains the pipe
 * buffer: DRAIN fires, and an over-cap line can never deadlock the parked writer. A line that
 * completes within one push decodes straight from the segment (no accumulation copy); a line
 * spanning pushes accumulates here (memory = the line itself, which is inherent). __linePush stops
 * just past the '\n', so trailing bytes stay queued (same tail rule as the JSON parser).
 *
 *   __lineNew()                 -> splitter (opaque; freed on GC; reusable across lines)
 *   __linePush(l, u8, off, len) -> bytes consumed (stops after a '\n')
 *   __lineDone(l)               -> bool: a complete line is ready
 *   __lineEnd(l)                -> the line (trailing '\r' stripped), or at EOF the partial
 *                                  remainder (raw), or null when nothing accumulated; RESETS the
 *                                  splitter for the next line.
 * (Holds only a JSString result — strings never participate in GC cycles, so a finalizer without
 * gc_mark is sufficient, unlike the parser/encoder which hold containers.) */

typedef struct {
    JSContext *ctx;
    DynBuf acc;     /* bytes of a line spanning pushes */
    JSValue result; /* the completed decoded line */
    int done;
} JLine;

static JSClassID jline_class_id;

static void jline_free(JLine *l) {
    if (l == NULL) return;
    JS_FreeValue(l->ctx, l->result);
    dbuf_free(&l->acc);
    free(l);
}

static void jline_class_finalizer(JSRuntime *rt, JSValue val) {
    JLine *l = JS_GetOpaque(val, jline_class_id);
    if (l) jline_free(l);
}

static JSClassDef jline_class_def = { "LineSplitter", .finalizer = jline_class_finalizer };

static JSValue js_line_new(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JLine *l = (JLine *)calloc(1, sizeof(JLine));
    if (l == NULL) return JS_ThrowOutOfMemory(ctx);
    l->ctx = ctx;
    l->result = JS_UNDEFINED;
    dbuf_init(&l->acc);
    JSValue obj = JS_NewObjectClass(ctx, jline_class_id);
    if (JS_IsException(obj)) { jline_free(l); return obj; }
    JS_SetOpaque(obj, l);
    return obj;
}

static JSValue js_line_push(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JLine *l = (JLine *)JS_GetOpaque2(ctx, argv[0], jline_class_id);
    if (l == NULL) return JS_EXCEPTION;
    if (l->done) return JS_ThrowInternalError(ctx, "__linePush: line already complete");
    int64_t off = 0, len = 0;
    JS_ToInt64(ctx, &off, argv[2]);
    JS_ToInt64(ctx, &len, argv[3]);
    size_t src_len = 0;
    uint8_t *src = view_bytes(ctx, argv[1], &src_len);
    if (!src) return JS_EXCEPTION;
    if (off < 0 || len < 0 || (uint64_t)off + (uint64_t)len > src_len)
        return JS_ThrowRangeError(ctx, "__linePush range out of bounds");
    const uint8_t *p = src + off;
    const uint8_t *hit = (const uint8_t *)memchr(p, '\n', (size_t)len);
    if (hit == NULL) {
        if (dbuf_put(&l->acc, p, (size_t)len)) return JS_ThrowOutOfMemory(ctx);
        return JS_NewInt64(ctx, len);
    }
    size_t j = (size_t)(hit - p);
    JSValue s;
    if (l->acc.size == 0) {
        /* fast path: the whole line lives in this push — decode straight from the segment */
        size_t e = (j > 0 && p[j - 1] == '\r') ? j - 1 : j;
        s = JS_NewStringLen(ctx, (const char *)p, e);
    } else {
        if (dbuf_put(&l->acc, p, j)) return JS_ThrowOutOfMemory(ctx);
        size_t e = (l->acc.size > 0 && l->acc.buf[l->acc.size - 1] == '\r') ? l->acc.size - 1 : l->acc.size;
        s = JS_NewStringLen(ctx, (const char *)l->acc.buf, e);
    }
    if (JS_IsException(s)) return s;
    l->result = s;
    l->done = 1;
    return JS_NewInt64(ctx, (int64_t)(j + 1));
}

static JSValue js_line_done(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JLine *l = (JLine *)JS_GetOpaque2(ctx, argv[0], jline_class_id);
    if (l == NULL) return JS_EXCEPTION;
    return JS_NewBool(ctx, l->done);
}

static JSValue js_line_end(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv) {
    JLine *l = (JLine *)JS_GetOpaque2(ctx, argv[0], jline_class_id);
    if (l == NULL) return JS_EXCEPTION;
    JSValue r;
    if (l->done) {
        r = l->result;
        l->result = JS_UNDEFINED;
        l->done = 0;
    } else if (l->acc.size > 0) {
        r = JS_NewStringLen(ctx, (const char *)l->acc.buf, l->acc.size); /* EOF remainder, raw */
    } else {
        r = JS_NULL;
    }
    l->acc.size = 0;
    if (l->acc.allocated_size > 65536) { dbuf_free(&l->acc); dbuf_init(&l->acc); } /* don't retain a huge line buffer */
    return r;
}

QjsPool *qjs_register_pipe(JSContext *ctx) {
    QjsPool *pool = (QjsPool *)calloc(1, sizeof(QjsPool));
    JS_SetRuntimeOpaque(JS_GetRuntime(ctx), pool);
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "__segTake", JS_NewCFunction(ctx, js_seg_take, "__segTake", 3));
    JS_SetPropertyStr(ctx, g, "__segRelease", JS_NewCFunction(ctx, js_seg_release, "__segRelease", 1));
    JS_SetPropertyStr(ctx, g, "__utf8Decode", JS_NewCFunction(ctx, js_utf8_decode, "__utf8Decode", 3));
    JS_SetPropertyStr(ctx, g, "__utf8Into", JS_NewCFunction(ctx, js_utf8_into, "__utf8Into", 5));
    JS_NewClassID(&jline_class_id);
    JS_NewClass(JS_GetRuntime(ctx), jline_class_id, &jline_class_def);
    JS_SetPropertyStr(ctx, g, "__lineNew", JS_NewCFunction(ctx, js_line_new, "__lineNew", 0));
    JS_SetPropertyStr(ctx, g, "__linePush", JS_NewCFunction(ctx, js_line_push, "__linePush", 4));
    JS_SetPropertyStr(ctx, g, "__lineDone", JS_NewCFunction(ctx, js_line_done, "__lineDone", 1));
    JS_SetPropertyStr(ctx, g, "__lineEnd", JS_NewCFunction(ctx, js_line_end, "__lineEnd", 1));
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
    JSValue u8 = new_u8_view(ctx, JS_DupValue(ctx, ab));
    if (JS_IsException(u8)) {
        /* never leave a live (non-detached) buffer over the caller's memory, and don't leak ab */
        JS_DetachArrayBuffer(ctx, ab);
        JS_FreeValue(ctx, ab);
        return u8;
    }
    *out_ab = ab;
    return u8;
}

void qjs_view_detach(JSContext *ctx, JSValue view, JSValue ab) {
    if (!JS_IsUndefined(ab)) JS_DetachArrayBuffer(ctx, ab);
    JS_FreeValue(ctx, ab);
    JS_FreeValue(ctx, view);
}
