#include "quickjs_web.h"
#include "cutils.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <limits.h>
#include <math.h>
#include <sys/time.h>

/* Flat-string access (YMusic patch at the end of vendored quickjs.c). */
JSValue JS_ToFlatString(JSContext *ctx, JSValueConst v);
int JS_GetFlatStringStorage(JSValueConst v, const void **pdata, uint32_t *plen);
JSValue JS_NewStringLatin1(JSContext *ctx, const char *buf, size_t len);

static const char B64[] =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

static int b64_digit(unsigned char c)
{
    if (c >= 'A' && c <= 'Z') return c - 'A';
    if (c >= 'a' && c <= 'z') return c - 'a' + 26;
    if (c >= '0' && c <= '9') return c - '0' + 52;
    if (c == '+' || c == '-') return 62;
    if (c == '/' || c == '_') return 63;
    return -1;
}

static int is_b64_space(unsigned char c)
{
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
}

static JSValue throw_invalid_char(JSContext *ctx)
{
    return JS_ThrowTypeError(ctx, "InvalidCharacterError");
}

static JSValue to_string_arg(JSContext *ctx, int argc, JSValueConst *argv)
{
    if (argc < 1)
        return JS_ThrowTypeError(ctx, "1 argument required");
    return JS_ToString(ctx, argv[0]);
}

/* Walk latin-1 bytes of a JS string without a UTF-8 round-trip. Wide strings
 * with a code unit > 255 fail (WHATWG btoa). */
static int latin1_bytes(JSContext *ctx, JSValueConst str,
                        const uint8_t **pbytes, uint32_t *plen, JSValue *keep)
{
    const void *data = NULL;
    uint32_t n = 0;
    int wide;
    JSValue flat;

    *keep = JS_UNDEFINED;
    flat = JS_ToFlatString(ctx, str);
    if (JS_IsException(flat))
        return -1;
    wide = JS_GetFlatStringStorage(flat, &data, &n);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        JS_ThrowTypeError(ctx, "not a string");
        return -1;
    }
    if (wide) {
        const uint16_t *w = (const uint16_t *)data;
        uint8_t *buf;
        uint32_t i;
        for (i = 0; i < n; i++) {
            if (w[i] > 255) {
                JS_FreeValue(ctx, flat);
                throw_invalid_char(ctx);
                return -1;
            }
        }
        buf = (uint8_t *)js_malloc(ctx, n ? n : 1);
        if (!buf) {
            JS_FreeValue(ctx, flat);
            return -1;
        }
        for (i = 0; i < n; i++)
            buf[i] = (uint8_t)w[i];
        JS_FreeValue(ctx, flat);
        *pbytes = buf;
        *plen = n;
        *keep = JS_NULL; /* marker: caller js_free(*pbytes) */
        return 0;
    }
    *pbytes = (const uint8_t *)data;
    *plen = n;
    *keep = flat; /* keep latin-1 storage alive */
    return 0;
}

static JSValue js_btoa(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    JSValue s, keep, out;
    const uint8_t *in;
    uint32_t n, i, j, out_len;
    uint8_t *dst;
    (void)this_val;

    s = to_string_arg(ctx, argc, argv);
    if (JS_IsException(s))
        return s;
    if (latin1_bytes(ctx, s, &in, &n, &keep)) {
        JS_FreeValue(ctx, s);
        return JS_EXCEPTION;
    }
    JS_FreeValue(ctx, s);
    out_len = ((n + 2) / 3) * 4;
    dst = (uint8_t *)js_malloc(ctx, out_len ? out_len : 1);
    if (!dst) {
        if (JS_IsNull(keep))
            js_free(ctx, (void *)in);
        else
            JS_FreeValue(ctx, keep);
        return JS_EXCEPTION;
    }
    j = 0;
    for (i = 0; i < n; i += 3) {
        unsigned a = in[i];
        unsigned b = i + 1 < n ? in[i + 1] : 0;
        unsigned c = i + 2 < n ? in[i + 2] : 0;
        dst[j++] = (uint8_t)B64[a >> 2];
        dst[j++] = (uint8_t)B64[((a & 3) << 4) | (b >> 4)];
        dst[j++] = (i + 1 < n) ? (uint8_t)B64[((b & 15) << 2) | (c >> 6)] : '=';
        dst[j++] = (i + 2 < n) ? (uint8_t)B64[c & 63] : '=';
    }
    if (JS_IsNull(keep))
        js_free(ctx, (void *)in);
    else
        JS_FreeValue(ctx, keep);
    out = JS_NewStringLatin1(ctx, (const char *)dst, out_len);
    js_free(ctx, dst);
    return out;
}

static JSValue js_atob(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    JSValue s, flat, out;
    const void *data = NULL;
    uint32_t n = 0, i, nvalid = 0, pad = 0, di = 0;
    int wide;
    uint8_t *dst;
    uint32_t acc = 0, nbits = 0;
    (void)this_val;

    s = to_string_arg(ctx, argc, argv);
    if (JS_IsException(s))
        return s;
    flat = JS_ToFlatString(ctx, s);
    JS_FreeValue(ctx, s);
    if (JS_IsException(flat))
        return flat;
    wide = JS_GetFlatStringStorage(flat, &data, &n);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "not a string");
    }
    /* Count digits; allow url-safe alphabet and '.'/'=' padding. */
    for (i = 0; i < n; i++) {
        unsigned c = wide ? ((const uint16_t *)data)[i] : ((const uint8_t *)data)[i];
        int d;
        if (c > 255) {
            JS_FreeValue(ctx, flat);
            return throw_invalid_char(ctx);
        }
        if (is_b64_space((unsigned char)c))
            continue;
        if (c == '=' || c == '.') {
            pad++;
            continue;
        }
        if (pad) {
            JS_FreeValue(ctx, flat);
            return throw_invalid_char(ctx);
        }
        d = b64_digit((unsigned char)c);
        if (d < 0) {
            JS_FreeValue(ctx, flat);
            return throw_invalid_char(ctx);
        }
        nvalid++;
    }
    dst = (uint8_t *)js_malloc(ctx, nvalid ? (nvalid * 3) / 4 + 1 : 1);
    if (!dst) {
        JS_FreeValue(ctx, flat);
        return JS_EXCEPTION;
    }
    for (i = 0; i < n; i++) {
        unsigned c = wide ? ((const uint16_t *)data)[i] : ((const uint8_t *)data)[i];
        int d;
        if (is_b64_space((unsigned char)c) || c == '=' || c == '.')
            continue;
        d = b64_digit((unsigned char)c);
        acc = (acc << 6) | (unsigned)d;
        nbits += 6;
        if (nbits >= 8) {
            nbits -= 8;
            dst[di++] = (uint8_t)((acc >> nbits) & 255);
        }
    }
    JS_FreeValue(ctx, flat);
    out = JS_NewStringLatin1(ctx, (const char *)dst, di);
    js_free(ctx, dst);
    return out;
}

/* ---- UTF-8 (same WTF-8 lone-surrogate rule as __utf8Encode) ---- */

static uint8_t *utf8_encode_units(const void *data, int wide, uint32_t i, uint32_t end,
                                  uint8_t *q)
{
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        for (; i < end; i++) {
            uint8_t c = sp[i];
            if (c < 0x80) {
                *q++ = c;
            } else {
                *q++ = (uint8_t)(0xC0 | (c >> 6));
                *q++ = (uint8_t)(0x80 | (c & 0x3F));
            }
        }
        return q;
    }
    {
        const uint16_t *sp = (const uint16_t *)data;
        while (i < end) {
            uint32_t c = sp[i];
            if (c < 0x80) {
                *q++ = (uint8_t)c;
                i++;
            } else if (c < 0x800) {
                *q++ = (uint8_t)(0xC0 | (c >> 6));
                *q++ = (uint8_t)(0x80 | (c & 0x3F));
                i++;
            } else if (c >= 0xD800 && c < 0xDC00 && i + 1 < end &&
                       sp[i + 1] >= 0xDC00 && sp[i + 1] < 0xE000) {
                uint32_t cp = 0x10000 + ((c - 0xD800) << 10) + (sp[i + 1] - 0xDC00);
                *q++ = (uint8_t)(0xF0 | (cp >> 18));
                *q++ = (uint8_t)(0x80 | ((cp >> 12) & 0x3F));
                *q++ = (uint8_t)(0x80 | ((cp >> 6) & 0x3F));
                *q++ = (uint8_t)(0x80 | (cp & 0x3F));
                i += 2;
            } else {
                *q++ = (uint8_t)(0xE0 | (c >> 12));
                *q++ = (uint8_t)(0x80 | ((c >> 6) & 0x3F));
                *q++ = (uint8_t)(0x80 | (c & 0x3F));
                i++;
            }
        }
        return q;
    }
}

static size_t utf8_measure_units(const void *data, int wide, uint32_t i, uint32_t end)
{
    size_t n = 0;
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        for (; i < end; i++) {
            size_t add = (sp[i] < 0x80) ? 1 : 2;
            if (n > SIZE_MAX - add)
                return SIZE_MAX;
            n += add;
        }
        return n;
    }
    {
        const uint16_t *sp = (const uint16_t *)data;
        while (i < end) {
            uint32_t c = sp[i];
            size_t add;
            if (c < 0x80) {
                add = 1;
                i++;
            } else if (c < 0x800) {
                add = 2;
                i++;
            } else if (c >= 0xD800 && c < 0xDC00 && i + 1 < end &&
                       sp[i + 1] >= 0xDC00 && sp[i + 1] < 0xE000) {
                add = 4;
                i += 2;
            } else {
                add = 3;
                i++;
            }
            if (n > SIZE_MAX - add)
                return SIZE_MAX;
            n += add;
        }
        return n;
    }
}

static void u8_buf_free(JSRuntime *rt, void *opaque, void *ptr)
{
    (void)opaque;
    js_free_rt(rt, ptr);
}

static JSValue utf8_to_uint8array(JSContext *ctx, JSValueConst str)
{
    JSValue flat, ab, u8, args[3];
    const void *data = NULL;
    uint32_t slen = 0;
    int wide;
    size_t n;
    uint8_t *buf = NULL;

    flat = JS_ToFlatString(ctx, str);
    if (JS_IsException(flat))
        return flat;
    wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "not a string");
    }
    n = utf8_measure_units(data, wide, 0, slen);
    if (n == SIZE_MAX) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowRangeError(ctx, "string too long");
    }
    if (n > 0) {
        buf = (uint8_t *)js_malloc(ctx, n);
        if (!buf) {
            JS_FreeValue(ctx, flat);
            return JS_EXCEPTION;
        }
        utf8_encode_units(data, wide, 0, slen, buf);
    }
    JS_FreeValue(ctx, flat);
    ab = JS_NewArrayBuffer(ctx, buf, n, buf ? u8_buf_free : NULL, NULL, 0);
    if (JS_IsException(ab)) {
        js_free(ctx, buf);
        return ab;
    }
    args[0] = ab;
    args[1] = JS_UNDEFINED;
    args[2] = JS_UNDEFINED;
    u8 = JS_NewTypedArray(ctx, 3, args, JS_TYPED_ARRAY_UINT8);
    JS_FreeValue(ctx, ab);
    return u8;
}

static JSValue js_textencoder_ctor(JSContext *ctx, JSValueConst new_target,
                                   int argc, JSValueConst *argv)
{
    JSValue proto, obj;
    (void)argc;
    (void)argv;
    if (JS_IsUndefined(new_target))
        return JS_ThrowTypeError(ctx, "TextEncoder constructor");
    proto = JS_GetPropertyStr(ctx, new_target, "prototype");
    if (JS_IsException(proto))
        return proto;
    obj = JS_NewObjectProto(ctx, proto);
    JS_FreeValue(ctx, proto);
    return obj;
}

static JSValue js_textencoder_encode(JSContext *ctx, JSValueConst this_val,
                                     int argc, JSValueConst *argv)
{
    JSValue s, u8;
    (void)this_val;
    if (argc < 1 || JS_IsUndefined(argv[0])) {
        JSValue e = JS_NewStringLatin1(ctx, "", 0);
        if (JS_IsException(e))
            return e;
        u8 = utf8_to_uint8array(ctx, e);
        JS_FreeValue(ctx, e);
        return u8;
    }
    s = JS_ToString(ctx, argv[0]);
    if (JS_IsException(s))
        return s;
    u8 = utf8_to_uint8array(ctx, s);
    JS_FreeValue(ctx, s);
    return u8;
}

static JSValue js_textencoder_encode_into(JSContext *ctx, JSValueConst this_val,
                                          int argc, JSValueConst *argv)
{
    JSValue s, flat, rec;
    const void *data = NULL;
    uint32_t slen = 0;
    int wide;
    size_t dlen = 0, ta_off = 0, ta_len = 0, bpe = 0;
    uint8_t *dst;
    JSValue ab;
    uint32_t written, read;
    (void)this_val;
    if (argc < 2)
        return JS_ThrowTypeError(ctx, "TextEncoder.encodeInto requires 2 arguments");
    s = JS_ToString(ctx, argv[0]);
    if (JS_IsException(s))
        return s;
    ab = JS_GetTypedArrayBuffer(ctx, argv[1], &ta_off, &ta_len, &bpe);
    if (JS_IsException(ab)) {
        JS_FreeValue(ctx, s);
        return ab;
    }
    dst = JS_GetArrayBuffer(ctx, &dlen, ab);
    JS_FreeValue(ctx, ab);
    if (!dst) {
        JS_FreeValue(ctx, s);
        return JS_ThrowTypeError(ctx, "detached buffer");
    }
    dst += ta_off;
    dlen = ta_len;
    (void)bpe;
    flat = JS_ToFlatString(ctx, s);
    JS_FreeValue(ctx, s);
    if (JS_IsException(flat))
        return flat;
    wide = JS_GetFlatStringStorage(flat, &data, &slen);
    if (wide < 0) {
        JS_FreeValue(ctx, flat);
        return JS_ThrowTypeError(ctx, "not a string");
    }
    /* Fill dest; stop before a code point that would overflow. */
    read = 0;
    written = 0;
    if (!wide) {
        const uint8_t *sp = (const uint8_t *)data;
        while (read < slen) {
            uint8_t c = sp[read];
            size_t need = c < 0x80 ? 1 : 2;
            if (written + need > dlen)
                break;
            utf8_encode_units(data, 0, read, read + 1, dst + written);
            written += (uint32_t)need;
            read++;
        }
    } else {
        const uint16_t *sp = (const uint16_t *)data;
        while (read < slen) {
            uint32_t c = sp[read];
            uint32_t adv = 1;
            size_t need;
            if (c < 0x80)
                need = 1;
            else if (c < 0x800)
                need = 2;
            else if (c >= 0xD800 && c < 0xDC00 && read + 1 < slen &&
                     sp[read + 1] >= 0xDC00 && sp[read + 1] < 0xE000) {
                need = 4;
                adv = 2;
            } else {
                need = 3;
            }
            if (written + need > dlen)
                break;
            utf8_encode_units(data, 1, read, read + adv, dst + written);
            written += (uint32_t)need;
            read += adv;
        }
    }
    JS_FreeValue(ctx, flat);
    rec = JS_NewObject(ctx);
    if (JS_IsException(rec))
        return rec;
    JS_SetPropertyStr(ctx, rec, "read", JS_NewUint32(ctx, read));
    JS_SetPropertyStr(ctx, rec, "written", JS_NewUint32(ctx, written));
    return rec;
}

static JSValue js_textencoder_get_encoding(JSContext *ctx, JSValueConst this_val)
{
    (void)this_val;
    return JS_NewString(ctx, "utf-8");
}

static uint8_t *buffer_source(JSContext *ctx, JSValueConst v, size_t *plen)
{
    size_t ta_off = 0, ta_len = 0, bpe = 0, sz = 0;
    JSValue ab;
    uint8_t *ptr;

    if (JS_IsUndefined(v) || JS_IsNull(v)) {
        *plen = 0;
        return (uint8_t *)"";
    }
    ab = JS_GetTypedArrayBuffer(ctx, v, &ta_off, &ta_len, &bpe);
    if (!JS_IsException(ab)) {
        ptr = JS_GetArrayBuffer(ctx, &sz, ab);
        JS_FreeValue(ctx, ab);
        if (!ptr) {
            JS_ThrowTypeError(ctx, "detached buffer");
            return NULL;
        }
        (void)bpe;
        *plen = ta_len;
        return ptr + ta_off;
    }
    JS_FreeValue(ctx, JS_GetException(ctx));
    ptr = JS_GetArrayBuffer(ctx, plen, v);
    if (ptr)
        return ptr;
    JS_ThrowTypeError(ctx, "TextDecoder.decode: BufferSource");
    return NULL;
}

static JSValue js_textdecoder_ctor(JSContext *ctx, JSValueConst new_target,
                                   int argc, JSValueConst *argv)
{
    JSValue proto, obj;
    if (JS_IsUndefined(new_target))
        return JS_ThrowTypeError(ctx, "TextDecoder constructor");
    if (argc > 0 && !JS_IsUndefined(argv[0]) && !JS_IsNull(argv[0])) {
        const char *label = JS_ToCString(ctx, argv[0]);
        int ok;
        if (!label)
            return JS_EXCEPTION;
        ok = !label[0] || !strcmp(label, "utf-8") || !strcmp(label, "utf8") ||
             !strcmp(label, "unicode-1-1-utf-8");
        JS_FreeCString(ctx, label);
        if (!ok)
            return JS_ThrowRangeError(ctx, "TextDecoder: unsupported encoding");
    }
    proto = JS_GetPropertyStr(ctx, new_target, "prototype");
    if (JS_IsException(proto))
        return proto;
    obj = JS_NewObjectProto(ctx, proto);
    JS_FreeValue(ctx, proto);
    return obj;
}

static JSValue js_textdecoder_decode(JSContext *ctx, JSValueConst this_val,
                                     int argc, JSValueConst *argv)
{
    size_t n = 0;
    uint8_t *p;
    (void)this_val;
    if (argc < 1)
        return JS_NewStringLatin1(ctx, "", 0);
    p = buffer_source(ctx, argv[0], &n);
    if (!p)
        return JS_EXCEPTION;
    return JS_NewStringLen(ctx, (const char *)p, n);
}

static JSValue js_textdecoder_get_encoding(JSContext *ctx, JSValueConst this_val)
{
    (void)this_val;
    return JS_NewString(ctx, "utf-8");
}

static int install_ctor(JSContext *ctx, JSValueConst global, const char *name,
                        JSCFunction *ctor, int length,
                        const JSCFunctionListEntry *proto, int proto_len)
{
    JSValue p, cls;
    p = JS_NewObject(ctx);
    JS_SetPropertyFunctionList(ctx, p, proto, proto_len);
    cls = JS_NewCFunction2(ctx, ctor, name, length, JS_CFUNC_constructor, 0);
    JS_SetConstructor(ctx, cls, p);
    JS_FreeValue(ctx, p);
    JS_DefinePropertyValueStr(ctx, global, name, cls, JS_PROP_C_W_E);
    return 0;
}

static const JSCFunctionListEntry textencoder_proto[] = {
    JS_CGETSET_DEF("encoding", js_textencoder_get_encoding, NULL),
    JS_CFUNC_DEF("encode", 1, js_textencoder_encode),
    JS_CFUNC_DEF("encodeInto", 2, js_textencoder_encode_into),
};

static const JSCFunctionListEntry textdecoder_proto[] = {
    JS_CGETSET_DEF("encoding", js_textdecoder_get_encoding, NULL),
    JS_CFUNC_DEF("decode", 1, js_textdecoder_decode),
};

static double g_perf_origin;
static double g_perf_last;

#define ENT_CAP 65536
#define ENT_CTX 8
typedef struct {
    JSContext *ctx;
    int rec;
    JSValue date_orig;
    JSValue rand_orig;
    double *date;
    double *perf;
    double *randv;
    int n_date;
    int n_perf;
    int n_rand;
} Ent;
static Ent g_ent[ENT_CTX];

static Ent *ent_of(JSContext *ctx)
{
    int i;
    if (!ctx)
        return NULL;
    for (i = 0; i < ENT_CTX; i++)
        if (g_ent[i].ctx == ctx)
            return &g_ent[i];
    for (i = 0; i < ENT_CTX; i++) {
        if (!g_ent[i].ctx) {
            g_ent[i].ctx = ctx;
            g_ent[i].date_orig = JS_UNDEFINED;
            g_ent[i].rand_orig = JS_UNDEFINED;
            return &g_ent[i];
        }
    }
    return NULL;
}

static void ent_push(double **buf, int *n, double v)
{
    if (*n >= ENT_CAP)
        return;
    if (!*buf) {
        *buf = (double *)malloc(sizeof(double) * ENT_CAP);
        if (!*buf)
            return;
    }
    (*buf)[(*n)++] = v;
}

static double clock_now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000.0 + tv.tv_usec / 1000.0;
}

static int64_t date_now_ms(void)
{
    struct timeval tv;
    gettimeofday(&tv, NULL);
    return (int64_t)tv.tv_sec * 1000 + (tv.tv_usec / 1000);
}

/* Chrome non-isolated performance.now is 100us. Date.now stays wall milliseconds. */
static JSValue js_perf_now(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    double n;
    Ent *e;
    (void)this_val;
    (void)argc;
    (void)argv;
    n = floor((clock_now_ms() - g_perf_origin) * 10.0 + 1e-9) / 10.0;
    if (n < g_perf_last)
        n = g_perf_last;
    g_perf_last = n;
    e = ent_of(ctx);
    if (e && e->rec)
        ent_push(&e->perf, &e->n_perf, n);
    return JS_NewFloat64(ctx, n);
}

/* Same gettimeofday integer ms as QuickJS js_Date_now. Record after the read. */
static JSValue js_date_now_rec(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    int64_t ms = date_now_ms();
    Ent *e = ent_of(ctx);
    (void)this_val;
    (void)argc;
    (void)argv;
    if (e && e->rec)
        ent_push(&e->date, &e->n_date, (double)ms);
    return JS_NewInt64(ctx, ms);
}

static JSValue js_math_random_rec(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    JSValue v;
    (void)argc;
    (void)argv;
    if (!e || JS_IsUndefined(e->rand_orig))
        return JS_EXCEPTION;
    v = JS_Call(ctx, e->rand_orig, this_val, 0, NULL);
    if (e->rec && !JS_IsException(v)) {
        double d;
        if (!JS_ToFloat64(ctx, &d, v))
            ent_push(&e->randv, &e->n_rand, d);
    }
    return v;
}

static JSValue js_bg_entropy_start(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    JSValue g, date, math;
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!e)
        return JS_UNDEFINED;
    e->n_date = e->n_perf = e->n_rand = 0;
    g = JS_GetGlobalObject(ctx);
    date = JS_GetPropertyStr(ctx, g, "Date");
    math = JS_GetPropertyStr(ctx, g, "Math");
    if (JS_IsUndefined(e->date_orig)) {
        e->date_orig = JS_GetPropertyStr(ctx, date, "now");
        JS_SetPropertyStr(ctx, date, "now", JS_NewCFunction(ctx, js_date_now_rec, "now", 0));
    }
    if (JS_IsUndefined(e->rand_orig)) {
        e->rand_orig = JS_GetPropertyStr(ctx, math, "random");
        JS_SetPropertyStr(ctx, math, "random", JS_NewCFunction(ctx, js_math_random_rec, "random", 0));
    }
    JS_FreeValue(ctx, date);
    JS_FreeValue(ctx, math);
    JS_FreeValue(ctx, g);
    e->rec = 1;
    return JS_UNDEFINED;
}

static JSValue js_bg_entropy_stop(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    JSValue g, date, math;
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!e)
        return JS_UNDEFINED;
    e->rec = 0;
    g = JS_GetGlobalObject(ctx);
    if (!JS_IsUndefined(e->date_orig)) {
        date = JS_GetPropertyStr(ctx, g, "Date");
        JS_SetPropertyStr(ctx, date, "now", JS_DupValue(ctx, e->date_orig));
        JS_FreeValue(ctx, date);
        JS_FreeValue(ctx, e->date_orig);
        e->date_orig = JS_UNDEFINED;
    }
    if (!JS_IsUndefined(e->rand_orig)) {
        math = JS_GetPropertyStr(ctx, g, "Math");
        JS_SetPropertyStr(ctx, math, "random", JS_DupValue(ctx, e->rand_orig));
        JS_FreeValue(ctx, math);
        JS_FreeValue(ctx, e->rand_orig);
        e->rand_orig = JS_UNDEFINED;
    }
    JS_FreeValue(ctx, g);
    return JS_UNDEFINED;
}

static JSValue ent_b64(JSContext *ctx, const double *v, int n)
{
    size_t nb = (size_t)n * sizeof(double);
    size_t out_len = ((nb + 2) / 3) * 4;
    char *out;
    const unsigned char *p = (const unsigned char *)v;
    size_t i, j = 0;
    if (n <= 0 || !v)
        return JS_NewString(ctx, "");
    out = (char *)malloc(out_len + 1);
    if (!out)
        return JS_EXCEPTION;
    for (i = 0; i + 2 < nb; i += 3) {
        unsigned t = (p[i] << 16) | (p[i + 1] << 8) | p[i + 2];
        out[j++] = B64[(t >> 18) & 63];
        out[j++] = B64[(t >> 12) & 63];
        out[j++] = B64[(t >> 6) & 63];
        out[j++] = B64[t & 63];
    }
    if (i < nb) {
        unsigned t = p[i] << 16;
        if (i + 1 < nb)
            t |= p[i + 1] << 8;
        out[j++] = B64[(t >> 18) & 63];
        out[j++] = B64[(t >> 12) & 63];
        out[j++] = (i + 1 < nb) ? B64[(t >> 6) & 63] : '=';
        out[j++] = '=';
    }
    out[j] = 0;
    {
        JSValue s = JS_NewStringLen(ctx, out, j);
        free(out);
        return s;
    }
}

static JSValue js_bg_entropy_dump(JSContext *ctx, JSValueConst this_val, int argc, JSValueConst *argv)
{
    Ent *e = ent_of(ctx);
    JSValue o;
    (void)this_val;
    (void)argc;
    (void)argv;
    if (!e || (e->n_date == 0 && e->n_perf == 0 && e->n_rand == 0))
        return JS_NULL;
    o = JS_NewObject(ctx);
    JS_SetPropertyStr(ctx, o, "nDate", JS_NewInt32(ctx, e->n_date));
    JS_SetPropertyStr(ctx, o, "nPerf", JS_NewInt32(ctx, e->n_perf));
    JS_SetPropertyStr(ctx, o, "nRand", JS_NewInt32(ctx, e->n_rand));
    JS_SetPropertyStr(ctx, o, "timeOrigin", JS_NewFloat64(ctx, g_perf_origin));
    JS_SetPropertyStr(ctx, o, "date", ent_b64(ctx, e->date, e->n_date));
    JS_SetPropertyStr(ctx, o, "perf", ent_b64(ctx, e->perf, e->n_perf));
    JS_SetPropertyStr(ctx, o, "rand", ent_b64(ctx, e->randv, e->n_rand));
    return o;
}

static void install_performance(JSContext *ctx, JSValueConst global)
{
    JSValue perf;
    g_perf_origin = clock_now_ms() - 1847.3;
    g_perf_last = 0;
    perf = JS_NewObject(ctx);
    JS_DefinePropertyValueStr(ctx, perf, "timeOrigin",
                              JS_NewFloat64(ctx, g_perf_origin), JS_PROP_C_W_E);
    JS_DefinePropertyValueStr(ctx, perf, "now",
                              JS_NewCFunction(ctx, js_perf_now, "now", 0), JS_PROP_C_W_E);
    JS_DefinePropertyValueStr(ctx, global, "performance", JS_DupValue(ctx, perf), JS_PROP_C_W_E);
    JS_FreeValue(ctx, perf);
}

void qjs_register_web(JSContext *ctx)
{
    JSValue g = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, g, "btoa", JS_NewCFunction(ctx, js_btoa, "btoa", 0));
    JS_SetPropertyStr(ctx, g, "atob", JS_NewCFunction(ctx, js_atob, "atob", 0));
    install_ctor(ctx, g, "TextEncoder", js_textencoder_ctor, 0,
                 textencoder_proto, countof(textencoder_proto));
    install_ctor(ctx, g, "TextDecoder", js_textdecoder_ctor, 0,
                 textdecoder_proto, countof(textdecoder_proto));
    install_performance(ctx, g);
    JS_SetPropertyStr(ctx, g, "__bgEntropyStart",
                      JS_NewCFunction(ctx, js_bg_entropy_start, "__bgEntropyStart", 0));
    JS_SetPropertyStr(ctx, g, "__bgEntropyStop",
                      JS_NewCFunction(ctx, js_bg_entropy_stop, "__bgEntropyStop", 0));
    JS_SetPropertyStr(ctx, g, "__bgEntropyDump",
                      JS_NewCFunction(ctx, js_bg_entropy_dump, "__bgEntropyDump", 0));
    JS_FreeValue(ctx, g);
}
